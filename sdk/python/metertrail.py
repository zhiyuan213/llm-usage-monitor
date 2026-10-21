"""Bounded asynchronous source SDK. No third-party runtime dependencies."""
import json
import os
import random
import threading
import time
import uuid
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse


class MeterTrail:
    def __init__(self, service_name, api_key=None, endpoint=None, environment="production", on_result=lambda x: None, on_error=lambda x: None):
        self.key = api_key or os.environ.get("METERTRAIL_API_KEY")
        self.endpoint = (endpoint or os.environ.get("METERTRAIL_ENDPOINT") or "").rstrip("/")
        if not self.key or not self.endpoint or not service_name:
            raise ValueError("api_key, endpoint, service_name are required")
        parsed = urlparse(self.endpoint)
        if parsed.scheme != "https" and parsed.hostname not in ("localhost", "127.0.0.1"):
            raise ValueError("HTTPS required outside localhost")
        self.agent = dict(serviceName=service_name, environment=environment, instanceId=str(uuid.uuid4()), sdkName="metertrail-python", sdkVersion="0.1.0")
        self.on_result, self.on_error = on_result, on_error
        self.queue, self.size, self.paused, self.pending = [], 0, None, None
        self.retry_at = 0
        self.lock, self.flush_lock = threading.Lock(), threading.Lock()
        self.stop, self.wake = threading.Event(), threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _notify(self, value):
        try:
            self.on_error(value)
        except Exception:
            pass

    def record(self, event):
        try:
            copied = dict(eventId=str(uuid.uuid4()), revision=1, startedAt=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
            copied.update(event)
            raw = json.dumps(copied, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
            copied = json.loads(raw)
        except (ValueError, TypeError):
            self._notify(dict(code="INVALID_EVENT"))
            return False
        error = None
        with self.lock:
            if self.stop.is_set():
                error = "CLOSED"
            elif len(raw) > 8192:
                error = "EVENT_TOO_LARGE"
            elif len(self.queue) >= 10000 or self.size + len(raw) > 16 * 1024 * 1024:
                error = "BUFFER_OVERFLOW"
            else:
                self.queue.append((copied, len(raw)))
                self.size += len(raw)
                if len(self.queue) >= 100 or self.size >= 256 * 1024:
                    self.wake.set()
        if error:
            self._notify(dict(code=error))
        return error is None

    def _request(self, path, body=None, deadline=None):
        for attempt in range(6):
            if deadline and time.monotonic() >= deadline:
                raise TimeoutError("FLUSH_TIMEOUT")
            try:
                request = Request(self.endpoint + path, data=None if body is None else json.dumps(body, separators=(",", ":")).encode(), headers={"Authorization": "Bearer " + self.key, "Content-Type": "application/json"})
                timeout = min(10, max(.1, deadline-time.monotonic())) if deadline else 10
                try:
                    response = urlopen(request, timeout=timeout)
                except HTTPError as e:
                    response = e
                with response:
                    status, headers = response.status, response.headers
                    data = json.loads(response.read())
                if status == 429 and data.get("code") != "QUOTA_EXCEEDED" and float(headers.get("Retry-After", 0)) > 0:
                    self.retry_at = time.monotonic() + float(headers.get("Retry-After"))
                    return status, data
                if status < 500 and not (status == 429 and data.get("code") != "QUOTA_EXCEEDED"):
                    return status, data
                if attempt == 5:
                    return status, data
                delay = max(float(headers.get("Retry-After", 0)), .25 * 2**attempt)
            except (URLError, OSError, ValueError):
                if attempt == 5:
                    raise
                delay = .25 * 2**attempt
            delay = min(10, delay + random.random() * .25)
            if deadline:
                delay = min(delay, max(0, deadline-time.monotonic()))
            time.sleep(delay)

    def _loop(self):
        while not self.stop.is_set():
            self.wake.wait(5)
            self.wake.clear()
            try:
                self.flush(timeout=30)
            except Exception as error:
                self._notify(dict(code="NETWORK_ERROR", message=str(error)))

    def flush(self, timeout=30):
        deadline = time.monotonic() + timeout
        if not self.flush_lock.acquire(timeout=timeout):
            return False
        try:
            while not self.paused and time.monotonic() < deadline and time.monotonic() >= self.retry_at:
                if self.pending:
                    batch_id, events = self.pending
                    status, result = self._request("/v1/batches/" + batch_id, deadline=deadline)
                    if status != 200:
                        self._notify(dict(code=result.get("code", "BATCH_QUERY_FAILED")))
                        return False
                    if result.get("status") == "pending":
                        time.sleep(.3)
                        continue
                    if result.get("status") != "completed":
                        self._notify(dict(code="BATCH_FAILED", batch_id=batch_id, status=result.get("status")))
                        self.pending = None
                        return False
                    for error in result.get("errors", []):
                        self._notify(dict(error, event=events[error["index"]]))
                        if error.get("code") == "QUOTA_EXCEEDED":
                            self.paused = "QUOTA_EXCEEDED"
                            event = events[error["index"]]
                            size = len(json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode())
                            with self.lock:
                                if len(self.queue) < 10000 and self.size + size <= 16*1024*1024:
                                    self.queue.append((event, size))
                                    self.size += size
                                else:
                                    self._notify(dict(code="BUFFER_OVERFLOW"))
                    try:
                        self.on_result(result)
                    except Exception:
                        pass
                    self.pending = None
                    continue
                with self.lock:
                    items, size = [], 0
                    for item in self.queue:
                        if len(items) >= 100 or (items and size+item[1] > 256*1024):
                            break
                        items.append(item)
                        size += item[1]
                if not items:
                    return True
                events = [x[0] for x in items]
                status, result = self._request("/v1/events/batch", dict(schemaVersion="1.0", agent=self.agent, events=events), deadline)
                if status == 202:
                    self.pending = (result["batchId"], events)
                elif status in (401, 403) or result.get("code") == "QUOTA_EXCEEDED":
                    self.paused = result.get("code", "INVALID_API_KEY")
                    self._notify(dict(code=self.paused))
                    return False
                elif status >= 500 or status == 429:
                    self._notify(dict(code=result.get("code", "RETRY_EXHAUSTED")))
                    return False
                elif status == 413 and len(items) > 1:
                    # Use individual sends after a server/proxy limit; identities stay unchanged.
                    for event in events:
                        single_status, single = self._request("/v1/events/batch", dict(schemaVersion="1.0", agent=self.agent, events=[event]), deadline)
                        if single_status != 202:
                            self._notify(dict(code=single.get("code", "PAYLOAD_TOO_LARGE")))
                            return False
                        while True:
                            poll_status, outcome = self._request("/v1/batches/"+single["batchId"], deadline=deadline)
                            if poll_status != 200:
                                return False
                            if outcome.get("status") != "pending":
                                for error in outcome.get("errors", []):
                                    self._notify(dict(error, event=event))
                                break
                            time.sleep(.3)
                else:
                    self._notify(dict(code=result.get("code", "PERMANENT_ERROR"), events=events))
                with self.lock:
                    del self.queue[:len(items)]
                    self.size -= size
            return False
        finally:
            self.flush_lock.release()

    def resume(self):
        self.paused = None
        self.wake.set()

    def check_quota(self):
        return self._request("/v1/quota")

    def close(self, timeout=10):
        self.stop.set()
        self.wake.set()
        return self.flush(timeout)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

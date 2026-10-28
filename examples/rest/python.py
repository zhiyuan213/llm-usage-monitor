import json
import os
import urllib.request
import uuid
from dataclasses import dataclass, fields, is_dataclass
from datetime import datetime, timezone
from typing import Any, Generic, TypeVar


@dataclass
class Agent:
    service_name: str
    environment: str
    instance_id: str


@dataclass
class Usage:
    input_tokens: int | None
    output_tokens: int | None
    cached_input_tokens: int | None


@dataclass
class TraceData:
    name: str
    status: str
    duration_ms: int
    user_id: str
    customer_id: str
    feature: str


@dataclass
class ObservationData:
    name: str
    observation_type: str
    status: str
    duration_ms: int
    provider: str
    model: str
    usage: Usage


T = TypeVar('T')


@dataclass
class Event(Generic[T]):
    event_id: str
    type: str
    object_id: str
    trace_id: str
    started_at: str
    data: T
    revision: int = 1
    span_id: str | None = None


@dataclass
class BatchRequest:
    schema_version: str
    agent: Agent
    events: list[Event[Any]]


@dataclass
class BatchReceipt:
    batch_id: str
    received: int
    status: str


def to_wire(value: Any) -> Any:
    """Convert Python names to protocol camelCase; retain unknown usage."""
    if is_dataclass(value):
        result = {}
        for field in fields(value):
            child = getattr(value, field.name)
            if child is None and not isinstance(value, Usage):
                continue
            first, *rest = field.name.split('_')
            result[first + ''.join(part.title() for part in rest)] = to_wire(child)
        return result
    if isinstance(value, list):
        return [to_wire(item) for item in value]
    return value


def create_sample_batch() -> BatchRequest:
    trace_id = uuid.uuid4().hex
    started_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
    trace = Event(
        event_id=str(uuid.uuid4()), type='trace', object_id=f'trace-{trace_id}',
        trace_id=trace_id, started_at=started_at,
        data=TraceData(name='Document analysis', status='success', duration_ms=2100,
                       user_id='demo-user', customer_id='demo-customer', feature='document-analysis'),
    )
    observation = Event(
        event_id=str(uuid.uuid4()), type='observation', object_id=f'obs-{trace_id}-1',
        trace_id=trace_id, span_id=uuid.uuid4().hex[:16], started_at=started_at,
        data=ObservationData(
            name='Model response', observation_type='generation', status='success',
            duration_ms=2000, provider='example', model='example-model',
            usage=Usage(input_tokens=1500, output_tokens=400, cached_input_tokens=0),
        ),
    )
    return BatchRequest(schema_version='1.0',
                        agent=Agent(service_name='document-api', environment='test', instance_id='demo-1'),
                        events=[trace, observation])


class MeterTrailClient:
    def __init__(self, *, endpoint: str, api_key: str) -> None:
        if not endpoint or not api_key:
            raise ValueError('endpoint and api_key are required')
        self.endpoint = endpoint.rstrip('/')
        self.api_key = api_key

    def send_batch(self, batch: BatchRequest) -> BatchReceipt:
        request = urllib.request.Request(
            self.endpoint + '/v1/events/batch',
            data=json.dumps(to_wire(batch)).encode('utf-8'),
            headers={'Authorization': 'Bearer ' + self.api_key, 'Content-Type': 'application/json'},
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            if response.status != 202:
                raise RuntimeError(f'Upload failed: HTTP {response.status}')
            result = json.load(response)
        return BatchReceipt(batch_id=result['batchId'], received=result['received'], status=result['status'])


def main() -> None:
    client = MeterTrailClient(endpoint=os.environ['METERTRAIL_ENDPOINT'],
                             api_key=os.environ['METERTRAIL_API_KEY'])
    batch = create_sample_batch()
    # Retry this batch unchanged, including every ID and timestamp.
    receipt = client.send_batch(batch)
    print(f'202 {receipt.status} batchId={receipt.batch_id}')


if __name__ == '__main__':
    main()

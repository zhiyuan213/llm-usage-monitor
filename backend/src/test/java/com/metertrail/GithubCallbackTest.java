package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

class GithubCallbackTest {
  @Test void cancellationConsumesStateClearsCookieAndNeverCreatesSession() throws Exception {
    for(String intent : new String[]{"login", "login:remember", UUID.randomUUID().toString()}) {
      var redis=mock(StringRedisTemplate.class);
      @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
      when(redis.opsForValue()).thenReturn(values);
      when(values.getAndDelete("oauth:test-state")).thenReturn(intent,null);
      var store=mock(Store.class);
      var auth=mock(AuthController.class);
      var controller=new GithubController(store,auth,mock(Challenges.class),redis,mock(TransactionTemplate.class),"client","secret","https://example.test");
      var req=new MockHttpServletRequest();req.setCookies(new Cookie("mt_oauth","test-state"));
      var res=new MockHttpServletResponse();
      controller.callback(null,"test-state","access_denied",req,res);
      assertEquals("https://example.test"+(intent.startsWith("login")?"/login":"/app/account")+"?oauth=cancelled",res.getRedirectedUrl());
      assertTrue(res.getHeader("Set-Cookie").contains("Max-Age=0"));
      assertFalse(res.getHeaders("Set-Cookie").stream().anyMatch(v->v.startsWith("mt_session=")));
      assertThrows(ApiError.class,()->controller.callback(null,"test-state","access_denied",req,new MockHttpServletResponse()));
      verifyNoInteractions(store);verify(auth,never()).session(any(),any(),anyBoolean());
    }
  }
  @Test void invalidOrMissingStateCannotUseErrorToBypassValidation() {
    var redis=mock(StringRedisTemplate.class);
    var controller=new GithubController(mock(Store.class),mock(AuthController.class),mock(Challenges.class),redis,mock(TransactionTemplate.class),"client","secret","https://example.test");
    for(String state:new String[]{"","forged"}) assertThrows(ApiError.class,()->controller.callback(null,state,"access_denied",new MockHttpServletRequest(),new MockHttpServletResponse()));
    verifyNoInteractions(redis);
  }
}

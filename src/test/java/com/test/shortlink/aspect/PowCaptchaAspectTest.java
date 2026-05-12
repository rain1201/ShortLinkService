package com.test.shortlink.aspect;

import com.test.shortlink.anno.PowCaptcha;
import com.test.shortlink.util.Util;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PowCaptchaAspectTest {

    @InjectMocks
    private PowCaptchaAspect powCaptchaAspect;

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private PowCaptcha powCaptcha;

    @Mock
    private Method method;

    private long validTime;
    private String[] paramNames = {"url", "expireAfter", "captcha", "time"};
    private String url = "http://example.com";
    private long expireAfter = 1000L;

    @BeforeEach
    void setUp() {
        validTime = System.currentTimeMillis() / 1000;
        
        // 默认注解行为打桩
        when(powCaptcha.timeParamName()).thenReturn("time");
        when(powCaptcha.captchaParamName()).thenReturn("captcha");
        when(powCaptcha.paramNames()).thenReturn(new String[]{"url", "expireAfter"});
    }

    private void setupJoinPointMock(Object[] args) {
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getParameterNames()).thenReturn(paramNames);
        when(joinPoint.getArgs()).thenReturn(args);
        when(methodSignature.getMethod()).thenReturn(method);
        when(method.getName()).thenReturn("shorten");
    }

    @Test
    void testBefore_Success() {
        // 生成合法的验证码
        String strToHash = "url" + url + "expireAfter" + expireAfter + validTime;
        String validCaptcha = Util.generatePowCaptcha(strToHash);
        Object[] args = {url, expireAfter, validCaptcha, validTime};
        setupJoinPointMock(args);

        assertDoesNotThrow(() -> powCaptchaAspect.before(joinPoint, powCaptcha));
    }

    @Test
    void testBefore_ExpiredTime() {
        long expiredTime = validTime - 1000; // 过期的时间
        Object[] args = {url, expireAfter, "dummyCaptcha", expiredTime};
        setupJoinPointMock(args);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, 
                () -> powCaptchaAspect.before(joinPoint, powCaptcha));
        assertEquals("Captcha expired", ex.getMessage());
    }

    @Test
    void testBefore_InvalidTimeType() {
        Object[] args = {url, expireAfter, "dummyCaptcha", "notALongTime"}; // 错误类型
        setupJoinPointMock(args);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, 
                () -> powCaptchaAspect.before(joinPoint, powCaptcha));
        assertEquals("time parameter must be of type long", ex.getMessage());
    }

    @Test
    void testBefore_InvalidCaptcha() {
        Object[] args = {url, expireAfter, "wrongCaptcha", validTime};
        setupJoinPointMock(args);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, 
                () -> powCaptchaAspect.before(joinPoint, powCaptcha));
        assertEquals("Invalid captcha", ex.getMessage());
    }

    @Test
    void testBefore_TargetParameterNotFound() {
        when(powCaptcha.paramNames()).thenReturn(new String[]{"missingParam"});
        Object[] args = {url, expireAfter, "dummyCaptcha", validTime};
        setupJoinPointMock(args);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, 
                () -> powCaptchaAspect.before(joinPoint, powCaptcha));
        assertEquals("Parameter missingParam not found", ex.getMessage());
    }
}
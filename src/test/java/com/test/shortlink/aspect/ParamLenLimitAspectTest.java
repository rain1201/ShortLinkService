package com.test.shortlink.aspect;

import com.test.shortlink.anno.ParamLenLimit;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParamLenLimitAspectTest {

    @InjectMocks
    private ParamLenLimitAspect paramLenLimitAspect;

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private ParamLenLimit paramLenLimit;

    private String[] paramNames = {"url", "code", "name"};
    private Object[] fullArgs = {"http://ex.com", "abc123", "John"};

    @BeforeEach
    void setUp() {
        lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
        lenient().when(methodSignature.getParameterNames()).thenReturn(paramNames);
        lenient().when(joinPoint.getArgs()).thenReturn(fullArgs);
    }

    @Test
    void testBefore_ValidLength() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"url", "code"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{20, 10});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{1, 1});

        assertDoesNotThrow(() -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
    }

    @Test
    void testBefore_ExceedsMaxLength() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"url"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{5});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{1});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void testBefore_BelowMinLength() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"code"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{10});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{10});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
        assertTrue(ex.getMessage().contains("code"));
    }

    @Test
    void testBefore_NonStringParameter() {
        Object[] argsWithInt = {"http://ex.com", 123, "John"};
        when(joinPoint.getArgs()).thenReturn(argsWithInt);
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"code"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{10});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{1});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
        assertTrue(ex.getMessage().contains("must be of type String"));
    }

    @Test
    void testBefore_MismatchedArrayLengths() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"url"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{10, 20});

        assertThrows(IllegalArgumentException.class,
                () -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
    }

    @Test
    void testBefore_MismatchedMinLenArrayLength() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"url", "code"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{10, 20});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
    }

    @Test
    void testBefore_MultipleValidParams() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"url", "code", "name"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{20, 10, 30});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{1, 1, 1});

        assertDoesNotThrow(() -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
    }

    @Test
    void testBefore_FirstParamFailsAmongMultiple() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{"url", "code"});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{5, 10});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{1, 1});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void testBefore_EmptyMaxLenArray() {
        when(paramLenLimit.paramNames()).thenReturn(new String[]{});
        when(paramLenLimit.maxLen()).thenReturn(new byte[]{});
        when(paramLenLimit.minLen()).thenReturn(new byte[]{});

        assertDoesNotThrow(() -> paramLenLimitAspect.before(joinPoint, paramLenLimit));
    }
}

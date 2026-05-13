package com.test.shortlink.anno;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({java.lang.annotation.ElementType.METHOD})
@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ParamLenLimit {
    String[] paramNames() default {};
    byte[] maxLen() default {};
    byte[] minLen() default {};
}

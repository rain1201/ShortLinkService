package com.test.shortlink.anno;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({java.lang.annotation.ElementType.METHOD})
@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface PowCaptcha {
    String value() default "";
    int level() default 1;
    String[] paramNames() default {};
    String captchaParamName() default "captcha";
    String timeParamName() default "time";
}

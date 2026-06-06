package com.test.shortlink.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.test.shortlink.anno.PowCaptcha;
import com.test.shortlink.dto.ApiResponse;
import com.test.shortlink.service.ShortlinkService;
import com.test.shortlink.util.Util;


@RestController
public class ShortlinkController {
    @Autowired
    private ShortlinkService shortlinkService;
    @Value("${app.redirect-code:302}")
    private int redirectCode;
    @Value("${app.default-expire-after:1000000000}")
    private long defaultExpireAfter;
    @Autowired
    private Environment env;
    private static final Logger logger = LoggerFactory.getLogger(ShortlinkController.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        boolean isDev = List.of(env.getActiveProfiles()).contains("dev");
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = "Internal Server Error";
        }
        if (isDev) {
            logger.error("An error occurred: ", e);
            return ResponseEntity.status(500).body(ApiResponse.error(500, msg));
        }
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Internal Server Error"));
    }

    @GetMapping("/index")
    public ApiResponse<String> index() {
        return ApiResponse.success("ok");
    }

    @PostMapping("/shorten")
    @PowCaptcha(paramNames={"url","expireAfter"},captchaParamName="captcha",timeParamName="time")
    public ApiResponse<String> shorten(@RequestParam("url") String url,
                        @RequestParam(value="expireAfter", required=false) Long expireAfter,
                        @RequestParam(value="updateCode",defaultValue="") String updateCode,
                        @RequestParam("captcha") String captcha,
                        @RequestParam("time") long time) {
        long expireAfterVal = expireAfter != null ? expireAfter : defaultExpireAfter;
        String id = shortlinkService.shorten(url, expireAfterVal, updateCode);
        return ApiResponse.success("Shortlink created", id);
    }

    @GetMapping("/{id:[^.]+}")
    public ResponseEntity<?> redirect(@PathVariable String id,
                                       @RequestHeader(value = "User-Agent", defaultValue = "") String userAgent,
                                       @RequestHeader(value = "X-Forwarded-For", defaultValue = "") String xForwardedFor) {
        ResponseEntity<?> response = ResponseEntity.status(redirectCode)
                                            .header("Location", shortlinkService.redirect(Util.strToId(id)))
                                            .build();
        shortlinkService.incrementViewCountAsync(Util.strToId(id), xForwardedFor, userAgent);
        return response;
    }

    @GetMapping("/getInfo/{id}")
    public ApiResponse<String> getViewCount(@PathVariable String id) {
        String info = shortlinkService.getInfo(id);
        return ApiResponse.success(info);
    }

    @PostMapping("/update/{id}")
    public ApiResponse<String> update(@PathVariable String id,
                                       @RequestParam(value = "url",defaultValue = "") String url,
                                       @RequestParam(value = "expireAfter",defaultValue = "-1") long expireAfter,
                                       @RequestParam("updateCode") String updateCode) {
        String msg = shortlinkService.update(id, url, expireAfter, updateCode);
        return ApiResponse.success(msg);
    }

    @PostMapping("/delete/{id}")
    public ApiResponse<String> delete(@PathVariable String id,
                                       @RequestParam("updateCode") String updateCode) {
        String msg = shortlinkService.delete(id, updateCode);
        return ApiResponse.success(msg);
    }

    @Profile("dev")
    @PostMapping("/calcUpdateCode/{id}")
    public ApiResponse<String> autoupdate(@PathVariable String id,
                                           @RequestParam(value = "url",defaultValue = "") String url,
                                           @RequestParam(value = "expireAfter",defaultValue = "-1") long expireAfter,
                                           @RequestParam("updateCode") String updateCode) {
        String code = Util.generateUpdateCode(updateCode, Util.strToId(id)+url+expireAfter);
        return ApiResponse.success(code);
    }

}

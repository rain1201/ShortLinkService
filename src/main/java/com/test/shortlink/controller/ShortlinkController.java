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
import com.test.shortlink.service.ShortlinkService;
import com.test.shortlink.util.Util;


@RestController
public class ShortlinkController {
    @Autowired
    private ShortlinkService shortlinkService;
    @Value("${app.redirect-code:302}")
    private int redirectCode;
    @Autowired
    private Environment env;
    private static final Logger logger = LoggerFactory.getLogger(ShortlinkController.class);
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        boolean isDev = List.of(env.getActiveProfiles()).contains("dev");
        if(isDev){
            logger.error("An error occurred: ", e);
            return ResponseEntity.status(500).body("Internal Server Error: " + e.getMessage()+e.getStackTrace().toString());
        }
        return ResponseEntity.status(500).body("Internal Server Error: " );
    }
    @GetMapping("/index")
    public String index() {
        return "index";
    }
    @PostMapping("/shorten")
    @PowCaptcha(paramNames={"url","expireAfter"},captchaParamName="captcha",timeParamName="time")
    public String shorten(@RequestParam("url") String url,
                        @RequestParam(value="expireAfter",defaultValue="1000000000") long expireAfter,
                        @RequestParam(value="updateCode",defaultValue="") String updateCode,
                        @RequestParam("captcha") String captcha,
                        @RequestParam("time") long time) {
        return shortlinkService.shorten(url,expireAfter,updateCode);
    }
    //@Operation(summary = "重定向", description = "返回注册页面的HTML视图")
    @GetMapping("/{id}")
    public ResponseEntity<String> redirect(@PathVariable String id,@RequestHeader(value = "User-Agent", defaultValue = "") String userAgent,
                                           @RequestHeader(value = "X-Forwarded-For", defaultValue = "") String xForwardedFor) {
        // 这里可以实现根据短链接ID重定向到原始URL的逻辑
        ResponseEntity<String> response = ResponseEntity.status(redirectCode)
                                            .header("Location", shortlinkService.redirect(Util.strToId(id)))
                                            .build();
        shortlinkService.incrementViewCountAsync(Util.strToId(id), xForwardedFor, userAgent);
        return response;
    }

    @GetMapping("/getInfo/{id}")
    public String getViewCount(@PathVariable String id) {
        return shortlinkService.getInfo(id)+"";
    }
    @PostMapping("/update/{id}")
    public String update(@PathVariable String id,@RequestParam(value = "url",defaultValue = "") String url,
                         @RequestParam(value = "expireAfter",defaultValue = "-1") long expireAfter,
                         @RequestParam("updateCode") String updateCode) {
        return shortlinkService.update(id, url, expireAfter, updateCode);
    }
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable String id,@RequestParam("updateCode") String updateCode) {
        return shortlinkService.delete(id, updateCode);
    }
    @Profile("dev")
    @PostMapping("/calcUpdateCode/{id}")
    public String autoupdate(@PathVariable String id,@RequestParam(value = "url",defaultValue = "") String url,
                         @RequestParam(value = "expireAfter",defaultValue = "-1") long expireAfter,
                         @RequestParam("updateCode") String updateCode) {
        return Util.generateUpdateCode(updateCode,Util.strToId(id)+url+expireAfter);
    }

}

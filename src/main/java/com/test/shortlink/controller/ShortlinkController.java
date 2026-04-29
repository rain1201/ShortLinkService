package com.test.shortlink.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.test.shortlink.service.ShortlinkService;

@RestController
public class ShortlinkController {
    @Autowired
    private ShortlinkService shortlinkService;
    @Value("${app.redirect-code:302}")
    private int redirectCode;
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        return ResponseEntity.status(500).body("Internal Server Error: " + e.getMessage()+e.getStackTrace().toString());
    }
    @GetMapping("/index")
    public String index() {
        return "index";
    }
    @PostMapping("/shorten")
    public String shorten(@RequestParam("url") String url,
                        @RequestParam(value="expireAfter",defaultValue="1000000000") long expireAfter,
                        @RequestParam(value="updateCode",defaultValue="") String updateCode) {
        return shortlinkService.shorten(url,expireAfter,updateCode);
    }
    //@Operation(summary = "重定向", description = "返回注册页面的HTML视图")
    @GetMapping("/{id}")
    public ResponseEntity<String> redirect(@PathVariable String id) {
        // 这里可以实现根据短链接ID重定向到原始URL的逻辑
        ResponseEntity<String> response = ResponseEntity.status(redirectCode)
                                            .header("Location", shortlinkService.redirect(id,true))
                                            .build();
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
}

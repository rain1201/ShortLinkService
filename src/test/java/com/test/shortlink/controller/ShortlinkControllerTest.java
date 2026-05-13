package com.test.shortlink.controller;

import com.test.shortlink.service.ShortlinkService;
import com.test.shortlink.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ShortlinkControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ShortlinkService shortlinkService;

    @Mock
    private Environment env;

    @InjectMocks
    private ShortlinkController shortlinkController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(shortlinkController, "redirectCode", 302);
        mockMvc = MockMvcBuilders.standaloneSetup(shortlinkController)
                .setControllerAdvice(shortlinkController) // 启用异常处理
                .build();
    }

    @Test
    void testIndex() throws Exception {
        mockMvc.perform(get("/index"))
                .andExpect(status().isOk())
                .andExpect(content().string("index"));
    }

    @Test
    void testShorten() throws Exception {
        String mockShortId = "encodedId123";
        when(shortlinkService.shorten(anyString(), anyLong(), anyString())).thenReturn(mockShortId);

        long time = System.currentTimeMillis()/1000;
        mockMvc.perform(post("/shorten")
                .param("url", "http://example.com")
                .param("expireAfter", "1000")
                .param("updateCode", "myCode")
                .param("time", String.valueOf(time))
                .param("captcha", Util.generatePowCaptcha("http://example.com1000"+time))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk())
                .andExpect(content().string(mockShortId));
    }

    @Test
    void testRedirect() throws Exception {
        String shortIdStr = Util.idToStr(123L);
        String targetUrl = "http://example.com";

        when(shortlinkService.redirect(anyLong())).thenReturn(targetUrl);

        mockMvc.perform(get("/{id}", shortIdStr))
                .andExpect(status().isFound()) // 302
                .andExpect(header().string("Location", targetUrl));
    }

    @Test
    void testHandleException() throws Exception {
        // 让 Service 抛出异常以测试 ExceptionHandler
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"}); // 模拟 dev 环境
        when(shortlinkService.shorten(anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("Test Error"));
        long time = System.currentTimeMillis()/1000;
        mockMvc.perform(post("/shorten")
                .param("url", "invalid")
                .param("expireAfter", "1000")
                .param("captcha", Util.generatePowCaptcha("invalid1000"+time))
                .param("time", String.valueOf(time))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Internal Server Error: Test Error")));
    }
    @Test
    void testGetInfo() throws Exception {
        String mockId = "encoded123";
        when(shortlinkService.getInfo(mockId)).thenReturn("Shortlink[Info...]");

        mockMvc.perform(get("/getInfo/{id}", mockId))
                .andExpect(status().isOk())
                .andExpect(content().string("Shortlink[Info...]"));
    }

    @Test
    void testUpdate() throws Exception {
        String mockId = "encoded123";
        when(shortlinkService.update(eq(mockId), anyString(), anyLong(), anyString()))
                .thenReturn("Shortlink updated successfully");

        mockMvc.perform(post("/update/{id}", mockId)
                .param("url", "http://new.com")
                .param("expireAfter", "3600")
                .param("updateCode", "myCode")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk())
                .andExpect(content().string("Shortlink updated successfully"));
    }

    @Test
    void testDelete() throws Exception {
        String mockId = "encoded123";
        when(shortlinkService.delete(eq(mockId), anyString()))
                .thenReturn("Shortlink deleted successfully");

        mockMvc.perform(post("/delete/{id}", mockId)
                .param("updateCode", "myCode")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk())
                .andExpect(content().string("Shortlink deleted successfully"));
    }

    @Test
    void testAutoUpdateCode() throws Exception {
        String mockId = Util.idToStr(123L);
        // 这个方法是 @Profile("dev") 激活的，走的是 Util 静态方法
        // 我们只需验证 endpoint 的连通性及调用不报错即可
        mockMvc.perform(post("/calcUpdateCode/{id}", mockId)
                .param("url", "http://test.com")
                .param("expireAfter", "1000")
                .param("updateCode", "baseCode")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }
}
package com.test.shortlink.controller;

import com.test.shortlink.service.ShortlinkService;
import com.test.shortlink.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

        mockMvc.perform(post("/shorten")
                .param("url", "http://example.com")
                .param("expireAfter", "1000")
                .param("updateCode", "myCode")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk())
                .andExpect(content().string(mockShortId));
    }

    @Test
    void testRedirect() throws Exception {
        String shortIdStr = Util.idToStr(123L);
        String targetUrl = "http://example.com";

        when(shortlinkService.redirect(anyLong(), eq(true))).thenReturn(targetUrl);

        mockMvc.perform(get("/{id}", shortIdStr))
                .andExpect(status().isFound()) // 302
                .andExpect(header().string("Location", targetUrl));
    }

    @Test
    void testHandleException() throws Exception {
        // 让 Service 抛出异常以测试 ExceptionHandler
        when(shortlinkService.shorten(anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("Test Error"));

        mockMvc.perform(post("/shorten")
                .param("url", "invalid")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Internal Server Error: Test Error")));
    }
}
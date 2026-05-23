package com.test.shortlink.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void testIsValidUrl() {
        // 有效的 URL
        assertTrue(Util.isValidUrl("http://example.com"));
        assertTrue(Util.isValidUrl("https://www.test.com/path?arg=1"));
        assertTrue(Util.isValidUrl("http://192.168.1.1:8080/api"));

        // 无效的 URL
        assertFalse(Util.isValidUrl("ftp://example.com")); // 不支持 ftp
        assertFalse(Util.isValidUrl("example.com"));       // 缺少 http
        assertFalse(Util.isValidUrl("htt://bad-url.com"));
    }

    @Test
    void testIdStrConversion() {
        long originalId = 123456789L;
        
        // 测试 ID 转 字符串
        String str = Util.idToStr(originalId);
        assertNotNull(str);
        
        // 测试 字符串 转回 ID
        long decodedId = Util.strToId(str);
        assertEquals(originalId, decodedId, "Encode and decode should be reversible");
    }

    @Test
    void testGenerateAndUpdateCode() {
        String realCode = "secret123";
        String updateString = "1http://example.com1000";

        // 测试生成代码
        String generatedCode = Util.generateUpdateCode(realCode, updateString);
        assertNotNull(generatedCode);
        assertEquals(8, generatedCode.length()); // 默认 checkCodeLength = 8

        // 测试校验代码是否通过
        assertTrue(Util.isValidUpdateCode(realCode, updateString, generatedCode));
        
        // 测试校验代码失败情况
        assertFalse(Util.isValidUpdateCode(realCode, updateString, "wrongcod"));
    }

    @Test
    void testSnowFlakeIdGeneration() {
        long id1 = Util.generateLinkId();
        long id2 = Util.generateLinkId();
        
        assertTrue(id1 > 0);
        assertTrue(id2 > 0);
        assertNotEquals(id1, id2, "Generated IDs should be unique");
    }

    @Test
    void testPowCaptchaCheck() {
        // 测试 PoW 验证
        String str = "testString";
        // 1. 生成正确的验证码
        String captcha = Util.generatePowCaptcha(str);
        
        // 2. 验证应通过
        assertTrue(Util.powCaptchaCheck(str, captcha));
        
        // 3. 验证错误的验证码应失败
        assertFalse(Util.powCaptchaCheck(str, "wrongCaptcha"));
    }

    @Test
    void testSnowFlakeId_InvalidWorkerId() {
        Exception e1 = assertThrows(IllegalArgumentException.class, () -> new SnowFlakeId(-1, 0));
        assertEquals("Worker ID must be between 0 and 31", e1.getMessage());

        Exception e2 = assertThrows(IllegalArgumentException.class, () -> new SnowFlakeId(0, 32));
        assertEquals("Datacenter ID must be between 0 and 31", e2.getMessage());
    }

    @Test
    void testLongToBytes_Roundtrip() {
        long[] testValues = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 123456789L};
        for (long val : testValues) {
            byte[] bytes = Util.longToBytes(val);
            assertEquals(8, bytes.length);
            long recovered = Util.bytesToLong(bytes);
            assertEquals(val, recovered);
        }
    }

    @Test
    void testLongToBytes_SpecificPattern() {
        long val = 0x0102030405060708L;
        byte[] bytes = Util.longToBytes(val);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, bytes);
    }

    @Test
    void testBytesToLong_Zero() {
        byte[] bytes = {0, 0, 0, 0, 0, 0, 0, 0};
        assertEquals(0L, Util.bytesToLong(bytes));
    }

    @Test
    void testIdToStr_Roundtrip() {
        long[] testValues = {0L, 1L, 1000L, 123456789L, 9876543210L};
        for (long val : testValues) {
            String str = Util.idToStr(val);
            assertNotNull(str);
            long recovered = Util.strToId(str);
            assertEquals(val, recovered);
        }
    }

    @Test
    void testGeneratePowCaptcha() {
        String str = "test-input";
        String captcha = Util.generatePowCaptcha(str);
        assertNotNull(captcha);
        assertTrue(captcha.length() > 0);
        assertTrue(Util.powCaptchaCheck(str, captcha));
    }

    @Test
    void testPowCaptchaCheck_EmptyString() {
        assertFalse(Util.powCaptchaCheck("", "wrong"));
    }

    @Test
    void testGenerateUpdateCode_EmptyRealCode() {
        String code = Util.generateUpdateCode("", "testString");
        assertNotNull(code);
        assertEquals(8, code.length());
    }

    @Test
    void testGenerateUpdateCode_DifferentInputs() {
        String code1 = Util.generateUpdateCode("secret", "data1");
        String code2 = Util.generateUpdateCode("secret", "data2");
        assertNotEquals(code1, code2);
    }

    @Test
    void testIsValidUrl_WithPort() {
        assertTrue(Util.isValidUrl("https://example.com:443/path"));
        assertTrue(Util.isValidUrl("http://example.com:8080/api"));
    }

    @Test
    void testIsValidUrl_WithPathAndQuery() {
        assertTrue(Util.isValidUrl("http://example.com/path/to/page?arg=1&b=2"));
        assertTrue(Util.isValidUrl("https://test.org/api/v1/users"));
    }

    @Test
    void testIsValidUrl_InsecureProtocol() {
        assertFalse(Util.isValidUrl("ftp://files.com"));
        assertFalse(Util.isValidUrl("file:///local/path"));
        assertFalse(Util.isValidUrl("javascript:alert(1)"));
    }

    @Test
    void testIsValidUpdateCode_EmptyProvidedCode() {
        assertFalse(Util.isValidUpdateCode("realCode", "data", ""));
    }

    @Test
    void testGenerateLinkId_ReturnsPositive() {
        long id = Util.generateLinkId();
        assertTrue(id > 0);
    }
}
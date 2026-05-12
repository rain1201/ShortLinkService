package com.test.shortlink.util;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.stereotype.Component;

@AutoConfiguration
@Component
public class Util {
    @Value("${app.check-code-length:8}")
    private static int checkCodeLength=8;
    @Value("${app.worker-id:0}")
    private static long workerId=0;
    @Value("${app.datacenter-id:0}")
    private static long datacenterId=0;
    @Value("${app.pow-difficulty:20}")
    private static int powDifficulty=2;
    @Value("${app.captcha-expire-seconds:300}")
    public static int captchaExpireSeconds=300;
    private static final Logger logger=LoggerFactory.getLogger(Util.class.getName());
    private static SnowFlakeId idGenerator = new SnowFlakeId(workerId, datacenterId);
    public static long generateLinkId() {
        return idGenerator.nextId();
    }
    public static boolean isValidUrl(String url){
        String re="^(https|http)\\:\\/\\/[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z0-9]{1,}(:[0-9]{1,5})?(\\/[\\S]*)?$"; 
        return url.matches(re);
    }
    public static boolean isValidUpdateCode(String realCode,String updateString,String providedCode) {
        String expectedCode=generateUpdateCode(realCode, updateString);
        return providedCode.equals(expectedCode);
    }
    public static String generateUpdateCode(String realCode,String updateString) {
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-1");
            md.reset();
            realCode=realCode.trim();
            updateString=updateString.trim();
            md.update((realCode+updateString).trim().getBytes("UTF-8"));
            String expectedCode= HexFormat.of().formatHex(md.digest());
            logger.info("Generated update code: {},[{}]", expectedCode,(realCode+updateString).trim());
            return expectedCode.substring(0, checkCodeLength);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
    public static boolean powCaptchaCheck(String str,String Captcha) {
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-1");
            md.reset();
            String input=str.trim()+Captcha.trim();
            md.update(input.getBytes("UTF-8"));
            byte[] hash=md.digest();
            for(int i=0;i<Integer.min(powDifficulty, hash.length);i++) {
                if(hash[i]!=0) {
                    return false;
                }
            }
            return true;
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
    public static String generatePowCaptcha(String str) {
        int nonce=0;
        while(true) {
            if(powCaptchaCheck(str+nonce, "")) {
                return String.valueOf(nonce);
            }
            nonce++;
        }
    }
    public static byte[] longToBytes(long x) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (x & 0xFF);
            x >>= 8;
        }
        return bytes;
    }
    public static long bytesToLong(byte[] bytes) {
        long x = 0;
        for (int i = 0; i < 8; i++) {
            x <<= 8;
            x |= (bytes[i] & 0xFF);
        }
        return x;
    }
    public static String idToStr(long id) {
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(longToBytes(id));
    }
    public static long strToId(String str) {
        Base64.Decoder decoder = Base64.getDecoder();
        return bytesToLong(decoder.decode(str));
    }
}

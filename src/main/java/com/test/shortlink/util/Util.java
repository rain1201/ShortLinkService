package com.test.shortlink.util;

import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;

public class Util {
    @Value("${app.check-code-length:8}")
    private static int checkCodeLength;
    public static String generateShortlink(String url) {
        return Long.toHexString(url.hashCode());
        
    }
    public static boolean isValidUrl(String url){
        String re="\\b(?:https?)://"+
                "(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,6}"+
                "|(?:\\d{1,3}\\.){3}\\d{1,3}"+
                "(?::\\d+)?"+
                "(?:/[^\\s]*)?\\b"; 
        return url.matches(re);
    }
    public static boolean isValidUpdateCode(String realCode,String updateString,String providedCode) {
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-1");
            md.update((realCode+updateString).getBytes("UTF-8"));
            String expectedCode= HexFormat.of().formatHex(md.digest());
            return providedCode.equals(expectedCode.substring(0, checkCodeLength));
        }catch(Exception e){
            return false;
        }
    }
}

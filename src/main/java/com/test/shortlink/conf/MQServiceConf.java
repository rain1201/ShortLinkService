package com.test.shortlink.conf;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import com.test.shortlink.service.MQService;
import com.test.shortlink.service.MQServiceFactory;

@AutoConfiguration
public class MQServiceConf {
    @Value("${app.mq.mqListenerNum:1}")
    int mqListenerNum;

    @Bean
    List<MQService> generateMQService() throws Exception{
        List<MQService> ret = new ArrayList<MQService>(mqListenerNum);
        for(int i=0;i<mqListenerNum;i++){
            ret.add(new MQServiceFactory(i).getObject());
        }
        return ret;
    }
}

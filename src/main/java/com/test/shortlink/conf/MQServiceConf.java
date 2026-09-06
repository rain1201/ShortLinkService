package com.test.shortlink.conf;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.Bean;

import com.test.shortlink.service.MQService;
import com.test.shortlink.service.MQServiceFactory;

import jakarta.annotation.PreDestroy;

@AutoConfiguration
public class MQServiceConf {
    @Value("${app.mq.listener-num:1}")
    int mqListenerNum;
    private final List<MQService> services = new ArrayList<>();

    @Bean
    List<MQService> generateMQService(AutowireCapableBeanFactory beanFactory) throws Exception{
        services.clear();
        for(int i=0;i<mqListenerNum;i++){
            MQService service = new MQServiceFactory(i).getObject();
            beanFactory.autowireBean(service);
            String beanName = "mqService-" + i;
            service = (MQService) beanFactory.initializeBean(service, beanName);
            services.add(service);
        }
        return services;
    }

    @PreDestroy
    public void shutdown() {
        services.forEach(MQService::shutdown);
    }
}

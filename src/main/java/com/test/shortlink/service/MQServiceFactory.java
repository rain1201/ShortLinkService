package com.test.shortlink.service;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.FactoryBean;

public class MQServiceFactory implements FactoryBean<MQService> {
    long threadId;
    public MQServiceFactory(long tid){
        threadId=tid;
    }
    @Override
    public @Nullable MQService getObject() throws Exception {
        return new MQService(threadId);
    }

    @Override
    public @Nullable Class<?> getObjectType() {
        return MQService.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
    
}

package com.github.jojotech.spring.cloud.commons.auto;

import com.github.jojotech.spring.cloud.commons.config.DefaultLoadBalancerConfiguration;
import com.github.jojotech.spring.cloud.commons.config.LoadBalancerConfiguration;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(LoadBalancerConfiguration.class)
@LoadBalancerClients(defaultConfiguration = DefaultLoadBalancerConfiguration.class)
public class LoadBalancerAutoConfiguration {
}

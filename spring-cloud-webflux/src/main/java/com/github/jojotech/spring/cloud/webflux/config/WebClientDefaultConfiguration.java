package com.github.jojotech.spring.cloud.webflux.config;

import com.alibaba.fastjson.JSON;
import com.github.jojotech.spring.cloud.webflux.webclient.WebClientNamedContextFactory;
import com.github.jojotech.spring.cloud.webflux.webclient.resilience4j.ClientResponseCircuitBreakerOperator;
import com.github.jojotech.spring.cloud.webflux.webclient.resilience4j.retry.ClientResponseRetryOperator;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.util.Map;

@Log4j2
@Configuration(proxyBeanMethods = false)
public class WebClientDefaultConfiguration {
    @Bean
    public WebClient getWebClient(
            ReactorLoadBalancerExchangeFilterFunction lbFunction,
            WebClientConfigurationProperties webClientConfigurationProperties,
            Environment environment,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        String name = environment.getProperty(WebClientNamedContextFactory.PROPERTY_NAME);
        Map<String, WebClientConfigurationProperties.WebClientProperties> configs = webClientConfigurationProperties.getConfigs();
        if (configs == null || configs.size() == 0) {
            throw new BeanCreationException("Failed to create webClient, please provide configurations under namespace: webclient.configs");
        }
        WebClientConfigurationProperties.WebClientProperties webClientProperties = configs.get(name);
        if (webClientProperties == null) {
            throw new BeanCreationException("Failed to create webClient, please provide configurations under namespace: webclient.configs." + name);
        }
        String serviceName = webClientProperties.getServiceName();
        //???????????????????????????????????????????????? key ?????????????????????
        if (StringUtils.isBlank(serviceName)) {
            serviceName = name;
        }
        String baseUrl = webClientProperties.getBaseUrl();
        //??????????????? baseUrl?????????????????????????????????
        if (StringUtils.isBlank(baseUrl)) {
            baseUrl = "http://" + serviceName;
        }

        Retry retry = null;
        try {
            retry = retryRegistry.retry(serviceName, serviceName);
        } catch (ConfigurationNotFoundException e) {
            retry = retryRegistry.retry(serviceName);
        }
        //???????????????????????????
        retry = Retry.of(serviceName, RetryConfig.from(retry.getRetryConfig()).retryOnException(throwable -> {
            //WebClientResponseException ?????????????????????????????? catch ??? WebClientResponseException ???????????????????????????????????? WebClientResponseException
            //?????? ClientResponseCircuitBreakerSubscriber ?????????
            if (throwable instanceof WebClientResponseException) {
                log.info("should retry on {}", throwable.toString());
                return true;
            }
            //???????????????????????????????????????????????????
            if (throwable instanceof CallNotPermittedException) {
                log.info("should retry on {}", throwable.toString());
                return true;
            }
            if (throwable instanceof WebClientRequestException) {
                WebClientRequestException webClientRequestException = (WebClientRequestException) throwable;
                HttpMethod method = webClientRequestException.getMethod();
                URI uri = webClientRequestException.getUri();
                //???????????????????????????????????????????????????????????????????????????????????? GET ??????????????????????????????????????????????????????
                boolean isResponseTimeout = false;
                Throwable cause = throwable.getCause();
                //netty ???????????????????????? ReadTimeoutException
                if (cause instanceof ReadTimeoutException) {
                    log.info("Cause is a ReadTimeoutException which indicates it is a response time out");
                    isResponseTimeout = true;
                } else {
                    //???????????????????????????????????? java ?????? nio ???????????? SocketTimeoutException???message ??? read time out
                    //????????????????????????????????? message ????????? read time out ????????????????????? message ??????
                    String message = throwable.getMessage();
                    if (StringUtils.isNotBlank(message) && StringUtils.containsIgnoreCase(message.replace(" ", ""), "readtimeout")) {
                        log.info("Throwable message contains readtimeout which indicates it is a response time out");
                        isResponseTimeout = true;
                    }
                }
                //??????????????? GET ???????????????????????????????????????????????????
                if (method == HttpMethod.GET || webClientProperties.retryablePathsMatch(uri.getPath())) {
                    log.info("should retry on {}-{}, {}", method, uri, throwable.toString());
                    return true;
                } else {
                    //???????????????????????????????????????????????????????????????
                    if (isResponseTimeout) {
                        log.info("should not retry on {}-{}, {}", method, uri, throwable.toString());
                    } else {
                        log.info("should retry on {}-{}, {}", method, uri, throwable.toString());
                        return true;
                    }
                }
            }
            return false;
        }).build());


        HttpClient httpClient = HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) webClientProperties.getConnectTimeout().toMillis())
                .doOnConnected(connection ->
                        connection
                                .addHandlerLast(new ReadTimeoutHandler((int) webClientProperties.getResponseTimeout().toSeconds()))
                                .addHandlerLast(new WriteTimeoutHandler((int) webClientProperties.getResponseTimeout().toSeconds()))
                );

        Retry finalRetry = retry;
        String finalServiceName = serviceName;
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                //Retry??????????????????
                .filter((clientRequest, exchangeFunction) -> {
                    return exchangeFunction
                            .exchange(clientRequest)
                            .transform(ClientResponseRetryOperator.of(finalRetry));
                })
                //????????????????????????url
                .filter(lbFunction)
                //?????????????????????????????????????????????????????????????????????
                .filter((clientRequest, exchangeFunction) -> {
                    CircuitBreaker circuitBreaker;
                    //????????????url??????????????????????????????????????????url
                    String instancId = clientRequest.url().getHost() + ":" + clientRequest.url().getPort();
                    try {
                        //????????????id???????????????????????????CircuitBreaker,??????serviceName????????????
                        circuitBreaker = circuitBreakerRegistry.circuitBreaker(instancId, finalServiceName);
                    } catch (ConfigurationNotFoundException e) {
                        circuitBreaker = circuitBreakerRegistry.circuitBreaker(instancId);
                    }
                    log.info("webclient circuit breaker [{}-{}] status: {}, data: {}", finalServiceName, instancId, circuitBreaker.getState(), JSON.toJSONString(circuitBreaker.getMetrics()));
                    return exchangeFunction.exchange(clientRequest).transform(ClientResponseCircuitBreakerOperator.of(circuitBreaker, webClientProperties));
                })
                .baseUrl(baseUrl)
                .build();
    }
}

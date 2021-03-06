package com.github.jojotech.spring.cloud.webmvc.feign;

import brave.Span;
import brave.Tracer;
import com.alibaba.fastjson.JSON;
import com.github.jojotech.spring.cloud.commons.metric.ServiceInstanceMetrics;
import com.github.jojotech.spring.cloud.webmvc.misc.SpecialHttpStatus;
import feign.Client;
import feign.Request;
import feign.Response;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.openfeign.FeignClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@Slf4j
public class Resilience4jFeignClient implements Client {
    private final ServiceInstanceMetrics serviceInstanceMetrics;
    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Tracer tracer;
    private ApacheHttpClient apacheHttpClient;


    public Resilience4jFeignClient(
            ServiceInstanceMetrics serviceInstanceMetrics, ApacheHttpClient apacheHttpClient,
            ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            Tracer tracer
    ) {
        this.serviceInstanceMetrics = serviceInstanceMetrics;
        this.apacheHttpClient = apacheHttpClient;
        this.threadPoolBulkheadRegistry = threadPoolBulkheadRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.tracer = tracer;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        FeignClient annotation = request.requestTemplate().methodMetadata().method().getDeclaringClass().getAnnotation(FeignClient.class);
        //??? Retry ????????????????????? contextId???????????????????????????
        String contextId = annotation.contextId();
        //??????????????????id
        String serviceInstanceId = getServiceInstanceId(contextId, request);
        //????????????+????????????id
        String serviceInstanceMethodId = getServiceInstanceMethodId(request);

        ThreadPoolBulkhead threadPoolBulkhead;
        CircuitBreaker circuitBreaker;
        try {
            //???????????????????????????
            threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(serviceInstanceId, contextId);
        } catch (ConfigurationNotFoundException e) {
            threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(serviceInstanceId);
        }
        try {
            //????????????????????????????????????resilience4j???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????resilience4j????????????
            circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceInstanceMethodId, contextId);
        } catch (ConfigurationNotFoundException e) {
            circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceInstanceMethodId);
        }
        //??????traceId
        Span span = tracer.currentSpan();
        ThreadPoolBulkhead finalThreadPoolBulkhead = threadPoolBulkhead;
        CircuitBreaker finalCircuitBreaker = circuitBreaker;
        Supplier<CompletionStage<Response>> completionStageSupplier = ThreadPoolBulkhead.decorateSupplier(threadPoolBulkhead,
                OpenfeignUtil.decorateSupplier(circuitBreaker, () -> {
                    try (Tracer.SpanInScope cleared = tracer.withSpanInScope(span)) {
                        log.info("call url: {} -> {}, ThreadPoolStats({}): {}, CircuitBreakStats({}): {}",
                                request.httpMethod(),
                                request.url(),
                                serviceInstanceId,
                                JSON.toJSONString(finalThreadPoolBulkhead.getMetrics()),
                                serviceInstanceMethodId,
                                JSON.toJSONString(finalCircuitBreaker.getMetrics())
                        );
                        Response execute = apacheHttpClient.execute(request, options);
                        log.info("response: {} - {}", execute.status(), execute.reason());
                        return execute;
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
        );
        ServiceInstance serviceInstance = getServiceInstance(request);
        try {
            serviceInstanceMetrics.recordServiceInstanceCall(serviceInstance);
            Response response = Try.ofSupplier(completionStageSupplier).get().toCompletableFuture().join();
            serviceInstanceMetrics.recordServiceInstanceCalled(serviceInstance, true);
            return response;
        } catch (CompletionException e) {
            serviceInstanceMetrics.recordServiceInstanceCalled(serviceInstance, false);
            //???????????????????????????????????????????????? CompletionException???????????????????????????????????? Exception
            Throwable cause = e.getCause();
            //??????????????????????????????????????????????????????
            if (cause instanceof CallNotPermittedException) {
                return Response.builder()
                        .request(request)
                        .status(SpecialHttpStatus.CIRCUIT_BREAKER_ON.getValue())
                        .reason(cause.getLocalizedMessage())
                        .requestTemplate(request.requestTemplate()).build();
            }
            //?????? IOException????????????????????????????????????????????????
            //?????? connect time out ????????????????????????????????????????????????????????????????????? read time out ??????????????????????????????????????????
            if (cause instanceof IOException) {
                boolean containsRead = cause.getMessage().toLowerCase().contains("read");
                if (containsRead) {
                    log.info("{}-{} exception contains read, which indicates the request has been sent", e.getMessage(), cause.getMessage());
                    //????????? read ???????????????????????????????????????????????????????????????????????? GET ??????????????? RetryableMethod ?????????????????? DefaultErrorDecoder ?????????
                    return Response.builder()
                            .request(request)
                            .status(SpecialHttpStatus.NOT_RETRYABLE_IO_EXCEPTION.getValue())
                            .reason(cause.getLocalizedMessage())
                            .requestTemplate(request.requestTemplate()).build();
                } else {
                    return Response.builder()
                            .request(request)
                            .status(SpecialHttpStatus.RETRYABLE_IO_EXCEPTION.getValue())
                            .reason(cause.getLocalizedMessage())
                            .requestTemplate(request.requestTemplate()).build();
                }
            }
            throw e;
        }
    }

    private ServiceInstance getServiceInstance(Request request) throws MalformedURLException {
        URL url = new URL(request.url());
        DefaultServiceInstance defaultServiceInstance = new DefaultServiceInstance();
        defaultServiceInstance.setHost(url.getHost());
        defaultServiceInstance.setPort(url.getPort());
        return defaultServiceInstance;
    }

    private String getServiceInstanceId(String contextId, Request request) throws MalformedURLException {
        URL url = new URL(request.url());
        return contextId + ":" + url.getHost() + ":" + url.getPort();
    }

    private String getServiceInstanceMethodId(Request request) throws MalformedURLException {
        URL url = new URL(request.url());
        //????????????????????? + ?????? + ??????????????????????????????id
        String methodName = request.requestTemplate().methodMetadata().method().toGenericString();
        return url.getHost() + ":" + url.getPort() + ":" + methodName;
    }
}

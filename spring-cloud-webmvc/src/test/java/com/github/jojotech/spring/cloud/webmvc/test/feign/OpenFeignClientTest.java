package com.github.jojotech.spring.cloud.webmvc.test.feign;

import brave.Span;
import brave.Tracer;
import com.github.jojotech.spring.cloud.commons.loadbalancer.RoundRobinWithRequestSeparatedPositionLoadBalancer;
import com.github.jojotech.spring.cloud.webmvc.feign.FeignDecoratorBuilderInterceptor;
import com.github.jojotech.spring.cloud.webmvc.feign.RetryableMethod;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.netflix.eureka.EurekaDiscoveryClientConfiguration;
import org.springframework.cloud.netflix.eureka.loadbalancer.LoadBalancerEurekaAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.mockito.Mockito.when;

//SpringRunner????????????MockitoJUnitRunner????????? @Mock ?????????????????????
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = {
        LoadBalancerEurekaAutoConfiguration.LOADBALANCER_ZONE + "=zone1",
        //?????? thread-pool-bulkhead ???????????????
        "management.endpoints.web.exposure.include=*",
        "feign.client.config.default.connectTimeout=500",
        "feign.client.config.default.readTimeout=2000",
        "feign.client.config." + OpenFeignClientTest.CONTEXT_ID_2 + ".readTimeout=4000",
        "resilience4j.thread-pool-bulkhead.configs.default.coreThreadPoolSize=" + OpenFeignClientTest.DEFAULT_THREAD_POOL_SIZE,
        "resilience4j.thread-pool-bulkhead.configs.default.maxThreadPoolSize=" + OpenFeignClientTest.DEFAULT_THREAD_POOL_SIZE,
        "resilience4j.thread-pool-bulkhead.configs." + OpenFeignClientTest.CONTEXT_ID_2 + ".coreThreadPoolSize=" + OpenFeignClientTest.TEST_SERVICE_2_THREAD_POOL_SIZE,
        "resilience4j.thread-pool-bulkhead.configs." + OpenFeignClientTest.CONTEXT_ID_2 + ".maxThreadPoolSize=" + OpenFeignClientTest.TEST_SERVICE_2_THREAD_POOL_SIZE,
        "resilience4j.circuitbreaker.configs.default.failureRateThreshold=" + OpenFeignClientTest.DEFAULT_FAILURE_RATE_THRESHOLD,
        "resilience4j.circuitbreaker.configs.default.slidingWindowType=TIME_BASED",
        "resilience4j.circuitbreaker.configs.default.slidingWindowSize=5",
        "resilience4j.circuitbreaker.configs.default.minimumNumberOfCalls=" + OpenFeignClientTest.DEFAULT_MINIMUM_NUMBER_OF_CALLS,
        "resilience4j.circuitbreaker.configs." + OpenFeignClientTest.CONTEXT_ID_2 + ".failureRateThreshold=" + OpenFeignClientTest.TEST_SERVICE_2_FAILURE_RATE_THRESHOLD,
        "resilience4j.circuitbreaker.configs." + OpenFeignClientTest.CONTEXT_ID_2 + ".minimumNumberOfCalls=" + OpenFeignClientTest.TEST_SERVICE_2_MINIMUM_NUMBER_OF_CALLS,
        "resilience4j.retry.configs.default.maxAttempts=" + OpenFeignClientTest.DEFAULT_RETRY,
        "resilience4j.retry.configs.default.waitDuration=500ms",
        "resilience4j.retry.configs.default.enableRandomizedWait=true",
        "resilience4j.retry.configs.default.randomizedWaitFactor=0.5",
        "resilience4j.retry.configs." + OpenFeignClientTest.CONTEXT_ID_2 + ".maxAttempts=" + OpenFeignClientTest.TEST_SERVICE_2_RETRY,
})
@Log4j2
public class OpenFeignClientTest {
    public static final String THREAD_ID_HEADER = "Threadid";
    public static final String TEST_SERVICE_1 = "testService1";
    public static final String CONTEXT_ID_1 = "testService1Client";
    public static final int DEFAULT_THREAD_POOL_SIZE = 10;
    public static final int DEFAULT_FAILURE_RATE_THRESHOLD = 50;
    public static final int DEFAULT_MINIMUM_NUMBER_OF_CALLS = 2;
    public static final int DEFAULT_RETRY = 3;
    public static final String TEST_SERVICE_2 = "testService2";
    public static final String CONTEXT_ID_2 = "testService2Client";
    public static final int TEST_SERVICE_2_THREAD_POOL_SIZE = 5;
    public static final int TEST_SERVICE_2_FAILURE_RATE_THRESHOLD = 30;
    public static final int TEST_SERVICE_2_MINIMUM_NUMBER_OF_CALLS = 10;
    public static final int TEST_SERVICE_2_RETRY = 2;
    public static final String TEST_SERVICE_3 = "testService3";
    public static final String CONTEXT_ID_3 = "testService3Client";

    @SpyBean
    private Tracer tracer;
    @SpyBean
    private TestService1Client testService1Client;
    @SpyBean
    private TestService2Client testService2Client;
    @SpyBean
    private LoadBalancerClientFactory loadBalancerClientFactory;
    @SpyBean
    private ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;
    @SpyBean
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @SpyBean
    private RetryRegistry retryRegistry;

    @SpringBootApplication(exclude = EurekaDiscoveryClientConfiguration.class)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Configuration
    public static class App {
        @Bean
        public ApacheHttpClientAop apacheHttpClientAop() {
            return new ApacheHttpClientAop();
        }

        @Bean
        public DiscoveryClient discoveryClient() {
            ServiceInstance service1Instance1 = Mockito.spy(ServiceInstance.class);
            ServiceInstance service2Instance2 = Mockito.spy(ServiceInstance.class);
            ServiceInstance service1Instance3 = Mockito.spy(ServiceInstance.class);
            ServiceInstance service1Instance4 = Mockito.spy(ServiceInstance.class);
            Map<String, String> zone1 = Map.ofEntries(
                    Map.entry("zone", "zone1")
            );
            when(service1Instance1.getMetadata()).thenReturn(zone1);
            when(service1Instance1.getInstanceId()).thenReturn("service1Instance1");
            when(service1Instance1.getHost()).thenReturn("httpbin.org");
            when(service1Instance1.getPort()).thenReturn(80);
            when(service2Instance2.getMetadata()).thenReturn(zone1);
            when(service2Instance2.getInstanceId()).thenReturn("service2Instance2");
            when(service2Instance2.getHost()).thenReturn("httpbin.org");
            when(service2Instance2.getPort()).thenReturn(80);
            when(service1Instance3.getMetadata()).thenReturn(zone1);
            when(service1Instance3.getInstanceId()).thenReturn("service1Instance3");
            //??????????????? httpbin.org ????????????????????????????????????????????? www
            when(service1Instance3.getHost()).thenReturn("www.httpbin.org");
            when(service1Instance3.getPort()).thenReturn(80);
            when(service1Instance4.getMetadata()).thenReturn(zone1);
            when(service1Instance4.getInstanceId()).thenReturn("service1Instance4");
            when(service1Instance4.getHost()).thenReturn("www.httpbin.org");
            //??????port?????????????????? IOException
            when(service1Instance4.getPort()).thenReturn(18080);
            DiscoveryClient spy = Mockito.spy(DiscoveryClient.class);
            Mockito.when(spy.getInstances(TEST_SERVICE_1))
                    .thenReturn(List.of(service1Instance1, service1Instance3));
            Mockito.when(spy.getInstances(TEST_SERVICE_2))
                    .thenReturn(List.of(service2Instance2));
            Mockito.when(spy.getInstances(TEST_SERVICE_3))
                    .thenReturn(List.of(service1Instance1, service1Instance4));
            return spy;
        }

        @Bean
        public TestService1ClientFallback testService1ClientFallback() {
            return new TestService1ClientFallback();
        }
    }

    @Aspect
    public static class ApacheHttpClientAop {
        //??????????????? ApacheHttpClient ??????
        @Pointcut("execution(* com.github.jojotech.spring.cloud.webmvc.feign.ApacheHttpClient.execute(..))")
        public void annotationPointcut() {
        }

        @Around("annotationPointcut()")
        public Object around(ProceedingJoinPoint pjp) throws Throwable {
            //?????? Header??????????????? Feign ??? RequestInterceptor???????????????????????????????????? ApacheHttpClient ??????????????????
            Request request = (Request) pjp.getArgs()[0];
            Field headers = ReflectionUtils.findField(Request.class, "headers");
            ReflectionUtils.makeAccessible(headers);
            Map<String, Collection<String>> map = (Map<String, Collection<String>>) ReflectionUtils.getField(headers, request);
            HashMap<String, Collection<String>> stringCollectionHashMap = new HashMap<>(map);
            stringCollectionHashMap.put(THREAD_ID_HEADER, List.of(String.valueOf(Thread.currentThread().getId())));
            ReflectionUtils.setField(headers, request, stringCollectionHashMap);
            return pjp.proceed();
        }
    }

    /**
     * ??????????????????
     */
    @Test
    public void testRetry() throws InterruptedException {
        Span span = tracer.nextSpan();
        try (Tracer.SpanInScope cleared = tracer.withSpanInScope(span)) {
            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            long l = span.context().traceId();
            RoundRobinWithRequestSeparatedPositionLoadBalancer loadBalancerClientFactoryInstance
                    = (RoundRobinWithRequestSeparatedPositionLoadBalancer) loadBalancerClientFactory.getInstance(TEST_SERVICE_1);
            AtomicInteger atomicInteger = loadBalancerClientFactoryInstance.getPositionCache().get(l);
            int start = atomicInteger.get();
            try {
                //get ???????????????
                testService1Client.testGetRetryStatus500();
            } catch (Exception e) {
            }
            Assertions.assertEquals(DEFAULT_RETRY, atomicInteger.get() - start);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            start = atomicInteger.get();
            try {
                //post ??????????????????
                testService1Client.testPostRetryStatus500();
            } catch (Exception e) {
            }
            Assertions.assertEquals(atomicInteger.get() - start, 1);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            loadBalancerClientFactoryInstance
                    = (RoundRobinWithRequestSeparatedPositionLoadBalancer) loadBalancerClientFactory.getInstance(TEST_SERVICE_2);
            atomicInteger = loadBalancerClientFactoryInstance.getPositionCache().get(l);
            start = atomicInteger.get();
            try {
                //get ???????????????????????? testservice 2 ????????????????????????????????????
                testService2Client.testGetRetryStatus500();
            } catch (Exception e) {
            }
            Assertions.assertEquals(atomicInteger.get() - start, TEST_SERVICE_2_RETRY);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            start = atomicInteger.get();
            try {
                //?????? post ????????????
                testService2Client.testPostRetryStatus500();
            } catch (Exception e) {
            }
            Assertions.assertEquals(atomicInteger.get() - start, 1);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            start = atomicInteger.get();
            try {
                //????????????????????????
                testService2Client.testPostWithAnnotationRetryStatus500();
            } catch (Exception e) {
            }
            Assertions.assertEquals(atomicInteger.get() - start, TEST_SERVICE_2_RETRY);
        }
    }

    /**
     * ?????? fallback ??????
     */
    @Test
    public void testFallback() {
        for (int i = 0; i < 10; i++) {
            String s = testService1Client.testGetRetryStatus500();
            Assertions.assertEquals(s, "fallback");
        }
        Assertions.assertThrows(RetryableException.class, () -> {
            testService2Client.testGetRetryStatus500();
        });
    }

    public static class TestService1ClientFallback implements TestService1Client {

        @Override
        public HttpBinAnythingResponse anything() {
            HttpBinAnythingResponse httpBinAnythingResponse = new HttpBinAnythingResponse();
            httpBinAnythingResponse.setData("fallback");
            return httpBinAnythingResponse;
        }

        @Override
        public String testCircuitBreakerStatus500() {
            return "fallback";
        }

        @Override
        public String testGetRetryStatus500() {
            return "fallback";
        }

        @Override
        public String testPostRetryStatus500() {
            return "fallback";
        }

        @Override
        public String testGetDelayOneSecond() {
            return "fallback";
        }

        @Override
        public String testGetDelayThreeSeconds() {
            return "fallback";
        }

        @Override
        public String testPostDelayThreeSeconds() {
            return "fallback";
        }
    }

    @FeignClient(name = TEST_SERVICE_1, contextId = CONTEXT_ID_1, configuration = TestService1ClientConfiguration.class)
    public interface TestService1Client {
        @GetMapping("/anything")
        HttpBinAnythingResponse anything();

        @GetMapping("/status/500")
        String testCircuitBreakerStatus500();

        @GetMapping("/status/500")
        String testGetRetryStatus500();

        @PostMapping("/status/500")
        String testPostRetryStatus500();

        @GetMapping("/delay/1")
        String testGetDelayOneSecond();

        @GetMapping("/delay/3")
        String testGetDelayThreeSeconds();

        @PostMapping("/delay/3")
        String testPostDelayThreeSeconds();
    }

    /**
     * ????????????????????????????????????
     *
     * @throws Exception
     */
    @Test
    public void testDifferentServiceWithDifferentThread() throws Exception {
        //?????????????????????
        circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
        Thread[] threads = new Thread[100];
        AtomicBoolean passed = new AtomicBoolean(true);
        //??????100???
        for (int i = 0; i < 100; i++) {
            threads[i] = new Thread(() -> {
                Span span = tracer.nextSpan();
                try (Tracer.SpanInScope cleared = tracer.withSpanInScope(span)) {
                    HttpBinAnythingResponse response = testService1Client.anything();
                    String threadId1 = response.getHeaders().get(THREAD_ID_HEADER);
                    response = testService2Client.anything();
                    String threadId2 = response.getHeaders().get(THREAD_ID_HEADER);
                    //???????????????????????????????????????????????????
                    if (StringUtils.equalsIgnoreCase(threadId1, threadId2)) {
                        passed.set(false);
                    }
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < 100; i++) {
            threads[i].join();
        }
        Assertions.assertTrue(passed.get());
    }

    /**
     * ??????????????????
     */
    @Test
    public void testConfigureThreadPool() {
        //?????????????????????
        circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
        //?????????????????? FeignClient ??????????????? NamedContext ????????????
        testService1Client.anything();
        testService2Client.anything();
        //??????????????????????????????????????????????????????????????????
        List<ThreadPoolBulkhead> threadPoolBulkheads = threadPoolBulkheadRegistry.getAllBulkheads().asJava();
        Set<String> collect = threadPoolBulkheads.stream().map(ThreadPoolBulkhead::getName)
                .filter(name -> name.contains(CONTEXT_ID_1) || name.contains(CONTEXT_ID_2)).collect(Collectors.toSet());
        Assertions.assertTrue(collect.size() >= 2);
        threadPoolBulkheads.forEach(threadPoolBulkhead -> {
            if (threadPoolBulkhead.getName().contains(CONTEXT_ID_1)) {
                Assertions.assertEquals(threadPoolBulkhead.getBulkheadConfig().getCoreThreadPoolSize(), DEFAULT_THREAD_POOL_SIZE);
                Assertions.assertEquals(threadPoolBulkhead.getBulkheadConfig().getMaxThreadPoolSize(), DEFAULT_THREAD_POOL_SIZE);
            } else if (threadPoolBulkhead.getName().contains(CONTEXT_ID_2)) {
                Assertions.assertEquals(threadPoolBulkhead.getBulkheadConfig().getCoreThreadPoolSize(), TEST_SERVICE_2_THREAD_POOL_SIZE);
                Assertions.assertEquals(threadPoolBulkhead.getBulkheadConfig().getMaxThreadPoolSize(), TEST_SERVICE_2_THREAD_POOL_SIZE);
            }
        });
    }

    /**
     * ??????????????????
     */
    @Test
    public void testConfigureCircuitBreaker() {
        //?????????????????????
        circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
        //?????????????????? FeignClient ??????????????? NamedContext ????????????
        testService1Client.anything();
        testService2Client.anything();
        //???????????????????????????????????????????????????????????????
        List<CircuitBreaker> circuitBreakers = circuitBreakerRegistry.getAllCircuitBreakers().asJava();
        Set<String> collect = circuitBreakers.stream().map(CircuitBreaker::getName)
                .filter(name -> {
                    try {
                        return name.contains(TestService1Client.class.getMethod("anything").toGenericString())
                                || name.contains(TestService2Client.class.getMethod("anything").toGenericString());
                    } catch (NoSuchMethodException e) {
                        return false;
                    }
                }).collect(Collectors.toSet());
        Assertions.assertEquals(collect.size(), 2);
        circuitBreakers.forEach(circuitBreaker -> {
            if (circuitBreaker.getName().contains(TestService1Client.class.getName())) {
                Assertions.assertEquals((int) circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold(), (int) DEFAULT_FAILURE_RATE_THRESHOLD);
                Assertions.assertEquals(circuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls(), DEFAULT_MINIMUM_NUMBER_OF_CALLS);
            } else if (circuitBreaker.getName().contains(TestService2Client.class.getName())) {
                Assertions.assertEquals((int) circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold(), (int) TEST_SERVICE_2_FAILURE_RATE_THRESHOLD);
                Assertions.assertEquals(circuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls(), TEST_SERVICE_2_MINIMUM_NUMBER_OF_CALLS);
            }
        });
    }

    /**
     * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     */
    @Test
    public void testCircuitBreakerOpenBasedOnServiceAndMethod() {
        //?????????????????????
        circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
        AtomicBoolean passed = new AtomicBoolean(false);
        for (int i = 0; i < 10; i++) {
            //???????????????????????????????????????????????? fallback ??????????????????????????????????????????????????????
            System.out.println(testService1Client.testCircuitBreakerStatus500());
            List<CircuitBreaker> circuitBreakers = circuitBreakerRegistry.getAllCircuitBreakers().asJava();
            circuitBreakers.stream().filter(circuitBreaker -> {
                return circuitBreaker.getName().contains("testCircuitBreakerStatus500")
                        && circuitBreaker.getName().contains("TestService1Client");
            }).findFirst().ifPresent(circuitBreaker -> {
                //???????????????????????????????????????????????????
                if (circuitBreaker.getState().equals(CircuitBreaker.State.OPEN)) {
                    passed.set(true);
                }
            });
            //?????? testCircuitBreakerStatus500 ????????????????????????????????? anything ?????????
            HttpBinAnythingResponse anything = testService1Client.anything();
            Assertions.assertNotEquals(anything.getData(), "fallback");
        }
        Assertions.assertTrue(passed.get());
    }

    /**
     * ??????????????????
     */
    @Test
    public void testConfigureRetry() {
        //?????????????????????
        circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
        //?????????????????? FeignClient ??????????????? NamedContext ????????????
        testService1Client.anything();
        testService2Client.anything();
        List<Retry> retries = retryRegistry.getAllRetries().asJava();
        //??????????????????????????????????????????????????????
        Set<String> collect = retries.stream().map(Retry::getName)
                .filter(name -> name.contains(CONTEXT_ID_1)
                        || name.contains(CONTEXT_ID_2)).collect(Collectors.toSet());
        Assertions.assertEquals(collect.size(), 2);
        retries.forEach(retry -> {
            if (retry.getName().contains(CONTEXT_ID_1)) {
                Assertions.assertEquals(retry.getRetryConfig().getMaxAttempts(), DEFAULT_RETRY);
            } else if (retry.getName().contains(CONTEXT_ID_2)) {
                Assertions.assertEquals(retry.getRetryConfig().getMaxAttempts(), TEST_SERVICE_2_RETRY);
            }
        });
    }

    @FeignClient(name = TEST_SERVICE_2, contextId = CONTEXT_ID_2)
    public interface TestService2Client {
        @GetMapping("/anything")
        HttpBinAnythingResponse anything();

        @GetMapping("/status/500")
        String testGetRetryStatus500();

        @PostMapping("/status/500")
        String testPostRetryStatus500();

        @RetryableMethod
        @PostMapping("/status/500")
        String testPostWithAnnotationRetryStatus500();

        @GetMapping("/delay/1")
        String testGetDelayOneSecond();

        @GetMapping("/delay/3")
        String testGetDelayThreeSeconds();
    }

    public static class TestService1ClientConfiguration {
        @Bean
        public FeignDecoratorBuilderInterceptor feignDecoratorBuilderInterceptor(
                TestService1ClientFallback testService1ClientFallback
        ) {
            return builder -> {
                builder.withFallback(testService1ClientFallback);
            };
        }

        @Bean
        public TestService1ClientFallback testService1ClientFallback() {
            return new TestService1ClientFallback();
        }
    }

    @FeignClient(name = TEST_SERVICE_3, contextId = CONTEXT_ID_3)
    public interface TestService3Client {
        @PostMapping("/anything")
        HttpBinAnythingResponse anything();
    }

    @SpyBean
    private TestService3Client testService3Client;

    /**
     * ???????????????????????????????????????????????????????????? connect timeout???????????????????????????
     */
    @Test
    public void testIOExceptionRetry() {
        //?????????????????????
        circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
        for (int i = 0; i < 5; i++) {
            Span span = tracer.nextSpan();
            try (Tracer.SpanInScope cleared = tracer.withSpanInScope(span)) {
                //????????????????????????????????????
                testService3Client.anything();
                testService3Client.anything();
            }
        }
    }

    /**
     * ?????? responseTimeout ???????????????
     * @throws InterruptedException
     */
    @Test
    public void testTimeOutAndRetry() throws InterruptedException {
        Span span = tracer.nextSpan();
        try (Tracer.SpanInScope cleared = tracer.withSpanInScope(span)) {
            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            long l = span.context().traceId();
            RoundRobinWithRequestSeparatedPositionLoadBalancer loadBalancerClientFactoryInstance
                    = (RoundRobinWithRequestSeparatedPositionLoadBalancer) loadBalancerClientFactory.getInstance(TEST_SERVICE_1);
            AtomicInteger atomicInteger = loadBalancerClientFactoryInstance.getPositionCache().get(l);
            int start = atomicInteger.get();
            //????????????????????????????????????????????????????????? fallback
            String s = testService1Client.testGetDelayOneSecond();
            Assertions.assertNotEquals(s, "fallback");
            //?????????????????????????????????
            Assertions.assertEquals(1, atomicInteger.get() - start);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            start = atomicInteger.get();
            //??????????????????????????????????????????????????? 3 ???
            s = testService1Client.testGetDelayThreeSeconds();
            Assertions.assertEquals(s, "fallback");
            Assertions.assertEquals(DEFAULT_RETRY, atomicInteger.get() - start);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            start = atomicInteger.get();
            //??????
            s = testService1Client.testPostDelayThreeSeconds();
            Assertions.assertEquals(s, "fallback");
            //?????? post ??????????????????????????????????????????
            Assertions.assertEquals(1, atomicInteger.get() - start);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            loadBalancerClientFactoryInstance
                    = (RoundRobinWithRequestSeparatedPositionLoadBalancer) loadBalancerClientFactory.getInstance(TEST_SERVICE_2);
            atomicInteger = loadBalancerClientFactoryInstance.getPositionCache().get(l);
            start = atomicInteger.get();
            //?????????
            s = testService2Client.testGetDelayOneSecond();
            Assertions.assertEquals(1, atomicInteger.get() - start);

            //?????????????????????
            circuitBreakerRegistry.getAllCircuitBreakers().asJava().forEach(CircuitBreaker::reset);
            start = atomicInteger.get();
            //???????????????????????????????????????????????? testService2Client ?????????
            s = testService2Client.testGetDelayThreeSeconds();
            Assertions.assertEquals(1, atomicInteger.get() - start);
        }
    }
}

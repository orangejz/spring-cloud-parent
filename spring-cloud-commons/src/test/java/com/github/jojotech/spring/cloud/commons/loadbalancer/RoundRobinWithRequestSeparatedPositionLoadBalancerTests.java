package com.github.jojotech.spring.cloud.commons.loadbalancer;

import java.util.ArrayList;

import com.github.jojotech.spring.cloud.commons.metric.ServiceInstanceMetrics;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;

import static org.mockito.Mockito.when;

class RoundRobinWithRequestSeparatedPositionLoadBalancerTests {
	@Test
	public void getInstanceResponseByRoundRobin() {
		DefaultServiceInstance service1Instance1 = new DefaultServiceInstance();
		service1Instance1.setHost("10.238.1.1");
		service1Instance1.setPort(1);
		DefaultServiceInstance service1Instance2 = new DefaultServiceInstance();
		service1Instance2.setHost("10.238.2.2");
		service1Instance2.setPort(2);
		DefaultServiceInstance service1Instance3 = new DefaultServiceInstance();
		service1Instance3.setHost("10.238.3.3");
		service1Instance3.setPort(3);
		DefaultServiceInstance service1Instance4 = new DefaultServiceInstance();
		service1Instance4.setHost("10.238.4.4");
		service1Instance4.setPort(4);
		ServiceInstanceListSupplier serviceInstanceListSupplier = Mockito.mock(ServiceInstanceListSupplier.class);
		String serviceId = "test";
		ServiceInstanceMetrics serviceInstanceMetrics = Mockito.mock(ServiceInstanceMetrics.class);
		RoundRobinWithRequestSeparatedPositionLoadBalancer roundRobinWithRequestSeparatedPositionLoadBalancer
				= new RoundRobinWithRequestSeparatedPositionLoadBalancer(serviceInstanceListSupplier, serviceId, null, serviceInstanceMetrics);
		ArrayList<ServiceInstance> serviceInstances = Lists
				.newArrayList(service1Instance1, service1Instance2, service1Instance3, service1Instance4);
		long traceId = 1234;
		when(serviceInstanceMetrics.getCalling(service1Instance1)).thenReturn(1L);
		when(serviceInstanceMetrics.getCalling(service1Instance2)).thenReturn(2L);
		when(serviceInstanceMetrics.getCalling(service1Instance3)).thenReturn(1L);
		when(serviceInstanceMetrics.getCalling(service1Instance4)).thenReturn(1L);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance1)).thenReturn(0.1);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance2)).thenReturn(0.1);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance3)).thenReturn(0.2);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance4)).thenReturn(0.3);
		Response<ServiceInstance> instanceResponseByRoundRobin = roundRobinWithRequestSeparatedPositionLoadBalancer
				.getInstanceResponseByRoundRobin(traceId, serviceInstances);
		//???????????????????????? 1 ????????? 2 ????????? ??????????????? ?????? 1 ??????????????? ?????? 2??????????????? ?????? 1
		Assertions.assertEquals(instanceResponseByRoundRobin.getServer(), service1Instance1);
		instanceResponseByRoundRobin = roundRobinWithRequestSeparatedPositionLoadBalancer
				.getInstanceResponseByRoundRobin(traceId, serviceInstances);
		//???????????? 1 ?????????????????????????????????????????? 2
		Assertions.assertEquals(instanceResponseByRoundRobin.getServer(), service1Instance2);
		instanceResponseByRoundRobin = roundRobinWithRequestSeparatedPositionLoadBalancer
				.getInstanceResponseByRoundRobin(traceId, serviceInstances);
		//?????????????????????????????? 3????????????????????? 3
		Assertions.assertEquals(instanceResponseByRoundRobin.getServer(), service1Instance3);
		instanceResponseByRoundRobin = roundRobinWithRequestSeparatedPositionLoadBalancer
				.getInstanceResponseByRoundRobin(traceId, serviceInstances);
		//?????????????????????????????? 4????????????????????? 4
		Assertions.assertEquals(instanceResponseByRoundRobin.getServer(), service1Instance4);
		when(serviceInstanceMetrics.getCalling(service1Instance1)).thenReturn(2L);
		when(serviceInstanceMetrics.getCalling(service1Instance2)).thenReturn(1L);
		when(serviceInstanceMetrics.getCalling(service1Instance3)).thenReturn(2L);
		when(serviceInstanceMetrics.getCalling(service1Instance4)).thenReturn(2L);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance1)).thenReturn(1.0);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance2)).thenReturn(1.0);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance3)).thenReturn(1.0);
		when(serviceInstanceMetrics.getFailedInRecentOneMin(service1Instance4)).thenReturn(1.0);
		instanceResponseByRoundRobin = roundRobinWithRequestSeparatedPositionLoadBalancer
				.getInstanceResponseByRoundRobin(traceId, serviceInstances);
		//???????????????????????????????????????????????????????????? 2 ??????????????????????????? 2
		Assertions.assertEquals(instanceResponseByRoundRobin.getServer(), service1Instance2);
	}
}
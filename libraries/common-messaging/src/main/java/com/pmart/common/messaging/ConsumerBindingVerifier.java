package com.pmart.common.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * {@link PmartEventConsumer} 빈마다 설정 저장소에 바인딩이 있는지 기동 때 확인한다(ADR-0035). 함수 이름이 {@code
 * spring.cloud.function.definition} 에 없거나 {@code
 * spring.cloud.stream.bindings.<fn>-in-0.destination} 이 없으면 Spring Cloud Stream 은 그 함수를 조용히 바인딩하지
 * 않는다 — 이벤트를 받지 않은 채 정상처럼 떠 있게 된다. 그래서 기동을 멈춘다.
 */
final class ConsumerBindingVerifier
    implements SmartInitializingSingleton, EnvironmentAware, BeanFactoryAware {

  private Environment environment;
  private ListableBeanFactory beans;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
    this.beans = (ListableBeanFactory) beanFactory;
  }

  @Override
  public void afterSingletonsInstantiated() {
    verify(beans.getBeanNamesForType(PmartEventConsumer.class), environment);
  }

  static void verify(String[] consumerBeans, Environment env) {
    List<String> declared =
        StreamBindingDefaultsPostProcessor.functions(
            env.getProperty(StreamBindingDefaultsPostProcessor.DEFINITION));
    List<String> problems = new ArrayList<>();
    for (String name : consumerBeans) {
      if (!declared.contains(name)) {
        problems.add(name + ": spring.cloud.function.definition 에 없음");
      } else if (env.getProperty("spring.cloud.stream.bindings." + name + "-in-0.destination")
          == null) {
        problems.add(name + ": spring.cloud.stream.bindings." + name + "-in-0.destination 없음");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "이벤트 소비 바인딩 설정이 빠졌습니다(설정 저장소 <서비스>.yml, ADR-0035): " + String.join("; ", problems));
    }
  }
}

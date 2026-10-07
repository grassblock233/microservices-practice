# week-03 项目提案与核心模型规划

## 本周计划

1. 编写项目提案文档：确认优先编写的业务场景：学生下单支付 → 商家接单，规划两个核心模型：订单（Order）与菜品（Dish）
2. 初步搭建单体Spring应用的骨架
3. 总结本周工作并提交

## 本周完成内容

1. **完成项目提案** [docs/project-proposal.md](../../project-proposal.md)
   - 明确四类目标用户学生、校园商家/食堂窗口、兼职骑手、运营管理员
   - 选定优先实现的业务场景：学生下单支付 → 商家接单
2. **细化优先实现的场景边界与状态流转**
   - 梳理场景步骤：浏览菜单 → 加购下单 → 模拟支付 → 生成已支付订单 → 商家接单/拒单
   - 确定订单状态流转：`待支付 → 已支付 → 已接单 / 已拒单`，`已关闭` 作为异常与取消终态
   - 明确本场景暂不实现：骑手抢单与配送轨迹、真实支付渠道、评价与售后、统计报表
3. **规划两个核心模型：订单（Order）与菜品（Dish）**
   - 订单：`orderNo`、`userId`、`merchantId`、`status`、`totalAmount`、`deliveryAddress` 等字段，核心行为是状态流转
   - 菜品：`merchantId`、`name`、`category`、`price`、`stock`、`status` 等字段，核心行为是上下架与库存扣减
   - 梳理模型关系：商家-菜品 1:N、学生-订单 1:N、订单-菜品经订单明细 M:N，并保留下单时单价快照
4. **搭建并验证单体应用骨架可运行**
   - `./mvnw spring-boot:run` 启动成功
   - 访问问候接口 `/api/hello` 返回 `Hello`，访问健康检查接口 `/actuator/health` 返回 `{"groups":["liveness","readiness"],"status":"UP"}`
   - `./mvnw test` 通过：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
5. **完善根目录 [README](../../../README.md) 的运行说明**
   - 补充 Java 与 Maven 版本要求、启动与测试命令
   - 补充问候接口与健康检查的访问地址
   - 补充当前尚未实现的业务能力清单，明确当前只有骨架、没有业务接口

## 项目运行输出
```bash
grass@yilong15pro:~/Desktop/microservices-practice/monolith$ ./mvnw spring-boot:run
[INFO] Scanning for projects...
[INFO] 
[INFO] ---------------------------< com.zjgsu:lht >----------------------------
[INFO] Building  0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] >>> spring-boot:4.0.8:run (default-cli) > test-compile @ lht >>>
[INFO] 
[INFO] --- resources:3.3.1:resources (default-resources) @ lht ---
[INFO] Copying 1 resource from src/main/resources to target/classes
[INFO] Copying 0 resource from src/main/resources to target/classes
[INFO] 
[INFO] --- compiler:3.14.1:compile (default-compile) @ lht ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- resources:3.3.1:testResources (default-testResources) @ lht ---
[INFO] skip non existing resourceDirectory /home/grass/Desktop/microservices-practice/monolith/src/test/resources
[INFO] 
[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ lht ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] <<< spring-boot:4.0.8:run (default-cli) < test-compile @ lht <<<
[INFO] 
[INFO] 
[INFO] --- spring-boot:4.0.8:run (default-cli) @ lht ---
[INFO] Attaching agents: []

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.0.8)

2026-10-07T20:24:58.495+08:00  INFO 26164 --- [lht] [           main] com.zjgsu.lht.LhtApplication             : Starting LhtApplication using Java 25.0.4.1 with PID 26164 (/home/grass/Desktop/microservices-practice/monolith/target/classes started by grass in /home/grass/Desktop/microservices-practice/monolith)
2026-10-07T20:24:58.497+08:00  INFO 26164 --- [lht] [           main] com.zjgsu.lht.LhtApplication             : No active profile set, falling back to 1 default profile: "default"
2026-10-07T20:24:58.936+08:00  INFO 26164 --- [lht] [           main] o.s.boot.tomcat.TomcatWebServer          : Tomcat initialized with port 8080 (http)
2026-10-07T20:24:58.944+08:00  INFO 26164 --- [lht] [           main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
2026-10-07T20:24:58.944+08:00  INFO 26164 --- [lht] [           main] o.apache.catalina.core.StandardEngine    : Starting Servlet engine: [Apache Tomcat/11.0.24]
2026-10-07T20:24:58.965+08:00  INFO 26164 --- [lht] [           main] b.w.c.s.WebApplicationContextInitializer : Root WebApplicationContext: initialization completed in 440 ms
2026-10-07T20:24:59.213+08:00  INFO 26164 --- [lht] [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 1 endpoint beneath base path '/actuator'
2026-10-07T20:24:59.239+08:00  INFO 26164 --- [lht] [           main] o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
2026-10-07T20:24:59.243+08:00  INFO 26164 --- [lht] [           main] com.zjgsu.lht.LhtApplication             : Started LhtApplication in 0.944 seconds (process running for 1.094)
2026-10-07T20:25:27.730+08:00  INFO 26164 --- [lht] [nio-8080-exec-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-10-07T20:25:27.730+08:00  INFO 26164 --- [lht] [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
2026-10-07T20:25:27.731+08:00  INFO 26164 --- [lht] [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 1 ms
```

## 接口响应

`/api/hello` : Hello

`/actuator/health` : {"groups":["liveness","readiness"],"status":"UP"}

## 测试结果

```bash
grass@yilong15pro:~/Desktop/microservices-practice/monolith$ ./mvnw test
[INFO] Scanning for projects...
[INFO] 
[INFO] ---------------------------< com.zjgsu:lht >----------------------------
[INFO] Building  0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.3.1:resources (default-resources) @ lht ---
[INFO] Copying 1 resource from src/main/resources to target/classes
[INFO] Copying 0 resource from src/main/resources to target/classes
[INFO] 
[INFO] --- compiler:3.14.1:compile (default-compile) @ lht ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- resources:3.3.1:testResources (default-testResources) @ lht ---
[INFO] skip non existing resourceDirectory /home/grass/Desktop/microservices-practice/monolith/src/test/resources
[INFO] 
[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ lht ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- surefire:3.5.6:test (default-test) @ lht ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.zjgsu.lht.LhtApplicationTests
20:33:40.242 [main] INFO org.springframework.test.context.support.AnnotationConfigContextLoaderUtils -- Could not detect default configuration classes for test class [com.zjgsu.lht.LhtApplicationTests]: LhtApplicationTests does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
20:33:40.299 [main] INFO org.springframework.boot.test.context.SpringBootTestContextBootstrapper -- Found @SpringBootConfiguration com.zjgsu.lht.LhtApplication for test class com.zjgsu.lht.LhtApplicationTests
20:33:40.347 [main] INFO org.springframework.test.context.support.AnnotationConfigContextLoaderUtils -- Could not detect default configuration classes for test class [com.zjgsu.lht.LhtApplicationTests]: LhtApplicationTests does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
20:33:40.349 [main] INFO org.springframework.boot.test.context.SpringBootTestContextBootstrapper -- Found @SpringBootConfiguration com.zjgsu.lht.LhtApplication for test class com.zjgsu.lht.LhtApplicationTests

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.0.8)

2026-10-07T20:33:40.535+08:00  INFO 27471 --- [lht] [           main] com.zjgsu.lht.LhtApplicationTests        : Starting LhtApplicationTests using Java 25.0.4.1 with PID 27471 (started by grass in /home/grass/Desktop/microservices-practice/monolith)
2026-10-07T20:33:40.537+08:00  INFO 27471 --- [lht] [           main] com.zjgsu.lht.LhtApplicationTests        : No active profile set, falling back to 1 default profile: "default"
2026-10-07T20:33:41.389+08:00  INFO 27471 --- [lht] [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 1 endpoint beneath base path '/actuator'
2026-10-07T20:33:41.427+08:00  INFO 27471 --- [lht] [           main] com.zjgsu.lht.LhtApplicationTests        : Started LhtApplicationTests in 1.043 seconds (process running for 1.582)
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/home/grass/.m2/repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.712 s -- in com.zjgsu.lht.LhtApplicationTests
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  2.776 s
[INFO] Finished at: 2026-10-07T20:33:41+08:00
[INFO] ------------------------------------------------------------------------
```
BASE=../streamflix-stack/core-engine

cat > "$BASE/pom.xml" << 'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.streamflix</groupId>
  <artifactId>core-engine</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <jersey.version>3.1.5</jersey.version>
    <jetty.version>11.0.20</jetty.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.eclipse.jetty</groupId>
      <artifactId>jetty-server</artifactId>
      <version>${jetty.version}</version>
    </dependency>
    <dependency>
      <groupId>org.eclipse.jetty</groupId>
      <artifactId>jetty-servlet</artifactId>
      <version>${jetty.version}</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jersey.containers</groupId>
      <artifactId>jersey-container-servlet</artifactId>
      <version>${jersey.version}</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jersey.media</groupId>
      <artifactId>jersey-media-json-jackson</artifactId>
      <version>${jersey.version}</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jersey.inject</groupId>
      <artifactId>jersey-hk2</artifactId>
      <version>${jersey.version}</version>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <version>42.7.3</version>
    </dependency>
  </dependencies>

  <build>
    <finalName>core-engine</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.streamflix.core.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
EOF

JAVA="$BASE/src/main/java/com/streamflix/core"

cat > "$JAVA/Main.java" << 'EOF'
package com.streamflix.core;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8981"));

        ResourceConfig config = new ResourceConfig();
        config.register(EventResource.class);
        config.register(AlarmResource.class);
        config.register(JacksonFeature.class);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");

        Server server = new Server(port);
        server.setHandler(context);

        System.out.println("core-engine (Jetty+Jersey) listening on :" + port);
        server.start();
        server.join();
    }
}
EOF

cat > "$JAVA/AlarmStore.java" << 'EOF'
package com.streamflix.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AlarmStore {
    public static final AlarmStore INSTANCE = new AlarmStore();

    // key = reduction key (uei + streamId) -- same concept as a monitoring
    // system's reduction-key: it's how repeated events collapse into one alarm.
    private final Map<String, Alarm> alarms = new ConcurrentHashMap<>();

    public void recordEvent(String uei, String streamId, String message) {
        String reductionKey = uei + ":" + streamId;

        alarms.compute(reductionKey, (key, existing) -> {
            if (uei.endsWith("Recovered")) {
                if (existing != null) {
                    existing.cleared = true;
                }
                return existing;
            }
            if (existing == null || existing.cleared) {
                return new Alarm(reductionKey, uei, streamId, message);
            }
            existing.occurrenceCount++;
            existing.lastMessage = message;
            return existing;
        });
    }

    public Map<String, Alarm> getActiveAlarms() {
        Map<String, Alarm> active = new ConcurrentHashMap<>();
        alarms.forEach((k, v) -> { if (!v.cleared) active.put(k, v); });
        return active;
    }

    public static class Alarm {
        public String reductionKey;
        public String uei;
        public String streamId;
        public String lastMessage;
        public int occurrenceCount = 1;
        public boolean cleared = false;

        public Alarm(String reductionKey, String uei, String streamId, String message) {
            this.reductionKey = reductionKey;
            this.uei = uei;
            this.streamId = streamId;
            this.lastMessage = message;
        }
    }
}
EOF

cat > "$JAVA/EventResource.java" << 'EOF'
package com.streamflix.core;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/events")
public class EventResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public String receiveEvent(Map<String, String> event) {
        String uei = event.get("uei");
        String streamId = event.get("streamId");
        String message = event.getOrDefault("message", "");

        AlarmStore.INSTANCE.recordEvent(uei, streamId, message);
        return "{\"status\":\"accepted\"}";
    }
}
EOF

cat > "$JAVA/AlarmResource.java" << 'EOF'
package com.streamflix.core;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/alarms")
public class AlarmResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, AlarmStore.Alarm> getAlarms() {
        return AlarmStore.INSTANCE.getActiveAlarms();
    }
}
EOF

cat > "$BASE/Dockerfile" << 'EOF'
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/core-engine.jar app.jar
EXPOSE 8981
CMD ["java", "-jar", "app.jar"]
EOF

echo "core-engine done"
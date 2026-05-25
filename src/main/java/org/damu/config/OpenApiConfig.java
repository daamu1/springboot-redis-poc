package org.damu.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring Boot Redis PoC API")
                        .description("Order, cart, queue, auth, and analytics APIs backed by Redis")
                        .version("v1")
                        .contact(new Contact().name("Damu Team"))
                        .license(new License().name("Internal Use")));
    }
}

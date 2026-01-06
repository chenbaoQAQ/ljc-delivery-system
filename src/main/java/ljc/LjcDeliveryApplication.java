package ljc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("ljc.mapper")
public class LjcDeliveryApplication {
    public static void main(String[] args) {
        SpringApplication.run(LjcDeliveryApplication.class, args);
    }
}
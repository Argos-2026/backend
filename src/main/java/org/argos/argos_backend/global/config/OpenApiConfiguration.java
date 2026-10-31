package org.argos.argos_backend.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI argosOpenApi() {
        return new OpenAPI()
            .info(new Info().title("ARGOS API").version("v1")
                .description("뉴스 URL 기반 주장별 사실 검증과 기사 내부 정보원의 IMVAIN 평가 API입니다. "
                    + "분석 요청 → 상태 조회 → 결과 조회 순서로 사용합니다. "
                    + "분석 작업과 결과는 접수 후 24시간 보관되며 서버 재시작 시 유실됩니다. "
                    + "사용자 인증은 필요하지 않으며 외부 서비스 API 키는 서버 환경변수로 설정합니다."))
            .servers(List.of(new Server().url("/").description("현재 서버")));
    }
}

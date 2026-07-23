package com.legacy.analysis.infra;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/advancement/3.confirmed/scenario_1_confirmed.md(2026-07-22 확정)에 스냅샷된 결정사항을
 * 코드로 고정한 명세(spec) 테스트. TDD 방식으로 먼저 작성됐으며, docker-compose.yml의 ollama/chroma
 * 배선·docker-compose.gpu.yml·.env.lite.example·docker/ollama-entrypoint.sh가 전부 아직 구현되지
 * 않은 시점에서는 대부분 RED(실패) 상태인 게 정상이다 — 구현이 끝나면 GREEN으로 전환되며,
 * 그 전환 자체가 "확정 스펙대로 구현됐다"는 증거가 된다.
 *
 * 실제 docker/ollama 런타임을 띄우지 않고 compose YAML/템플릿 파일의 정적 구조만 검증한다
 * (예: healthcheck 타이밍이 실제로 충분한지, entrypoint 스크립트가 실제로 동작하는지는 이 테스트의
 * 범위 밖 — 그건 scenario_1_test.md의 "클린 환경 기동 확인" 항목이 다룬다).
 */
class Scenario1LiteDeploymentSpecTest {

    private static final Path COMPOSE_FILE = Paths.get("docker-compose.yml");

    private Map<String, Object> loadDockerCompose() throws IOException {
        assertThat(Files.exists(COMPOSE_FILE)).as("docker-compose.yml 파일 자체는 이미 존재해야 한다").isTrue();
        try (InputStream in = Files.newInputStream(COMPOSE_FILE)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> service(Map<String, Object> compose, String name) {
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return (Map<String, Object>) services.get(name);
    }

    @SuppressWarnings("unchecked")
    @Test
    void postgres_서비스는_변경없이_유지되고_h2_프로필은_비상_fallback으로_보존된다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> postgres = service(compose, "postgres");

        assertThat(postgres).as("postgres 서비스가 사라지면 안 된다 — h2로 전환하지 않기로 확정됨").isNotNull();
        assertThat(postgres.get("image")).isEqualTo("postgres:16-alpine");
        assertThat(postgres.containsKey("profiles"))
                .as("postgres는 profiles 제한 없이 항상 기동돼야 한다")
                .isFalse();

        assertThat(Files.exists(Paths.get("src/main/resources/application-h2.properties")))
                .as("h2 프로필은 삭제하지 않고 postgres 기동 실패 시 fallback으로 남겨두기로 확정됨")
                .isTrue();
    }

    @Test
    void 별도_docker_compose_lite_yml_파일은_만들지_않는다() {
        assertThat(Files.exists(Paths.get("docker-compose.lite.yml")))
                .as("기존 docker-compose.yml에 profiles(llm-rag)로 통합하기로 확정 — 별도 lite 파일 없음")
                .isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void app_서비스는_image와_build를_함께_명시한다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> app = service(compose, "app");

        assertThat(app.get("image"))
                .as("2단계 pull 전환 대비 image: 키를 처음부터 박아둬야 한다")
                .isEqualTo("it1657/legacy-analyzer:latest");

        Object buildRaw = app.get("build");
        assertThat(buildRaw).as("1단계는 build로 실사용하므로 build: 도 함께 있어야 한다").isNotNull();
        Map<String, Object> build = (Map<String, Object>) buildRaw;
        assertThat(build.get("context")).isEqualTo(".");
        assertThat(build.get("dockerfile")).isEqualTo("Dockerfile");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ollama_서비스는_공식이미지를_커스텀빌드없이_llm_rag_프로필로만_기동한다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> ollama = service(compose, "ollama");

        assertThat(ollama).as("ollama 서비스가 아직 docker-compose.yml에 정의되지 않음(시나리오1 1단계 미구현)").isNotNull();
        assertThat(ollama.get("image")).isEqualTo("ollama/ollama:0.32.1");
        assertThat(ollama.containsKey("build"))
                .as("공식 이미지를 그대로 pull해서 쓰는 원칙 — 커스텀 빌드 금지")
                .isFalse();

        Object profilesRaw = ollama.get("profiles");
        assertThat(profilesRaw).as("ollama는 llm-rag 프로필 없이는 기동되면 안 된다").isNotNull();
        assertThat((List<Object>) profilesRaw).containsExactly("llm-rag");
    }

    @SuppressWarnings("unchecked")
    @Test
    void chroma_서비스는_llm_rag_프로필로만_기동한다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> chroma = service(compose, "chroma");

        assertThat(chroma).as("chroma 서비스가 아직 docker-compose.yml에 정의되지 않음(시나리오1 1단계 미구현)").isNotNull();
        assertThat(chroma.get("image")).isEqualTo("chromadb/chroma:1.5.9");

        Object profilesRaw = chroma.get("profiles");
        assertThat(profilesRaw).as("chroma는 llm-rag 프로필 없이는 기동되면 안 된다").isNotNull();
        assertThat((List<Object>) profilesRaw).containsExactly("llm-rag");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ollama_entrypoint는_이미지를_굽지않고_바인드마운트로만_오버라이드된다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> ollama = service(compose, "ollama");
        assertThat(ollama).as("ollama 서비스가 아직 정의되지 않음").isNotNull();

        assertThat(ollama.get("entrypoint"))
                .as("커스텀 entrypoint 오버라이드가 배선돼야 한다")
                .isEqualTo(List.of("/bin/sh", "/entrypoint.sh"));

        Object volumesRaw = ollama.get("volumes");
        assertThat(volumesRaw).as("entrypoint 스크립트를 얹을 volumes 배선이 없다").isNotNull();
        List<String> volumes = (List<String>) volumesRaw;
        assertThat(volumes)
                .as("docker/ollama-entrypoint.sh를 :ro 바인드 마운트로 얹어야 한다")
                .anyMatch(v -> v.contains("docker/ollama-entrypoint.sh") && v.contains(":/entrypoint.sh"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void ollama_healthcheck는_모델_pull_완료여부를_확인하고_확정된_타이밍값을_따른다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> ollama = service(compose, "ollama");
        assertThat(ollama).as("ollama 서비스가 아직 정의되지 않음").isNotNull();

        Object healthcheckRaw = ollama.get("healthcheck");
        assertThat(healthcheckRaw)
                .as("healthcheck가 없으면 app이 모델 준비 전에도 ollama를 호출할 수 있다")
                .isNotNull();
        Map<String, Object> healthcheck = (Map<String, Object>) healthcheckRaw;

        List<String> test = (List<String>) healthcheck.get("test");
        String testCmd = String.join(" ", test);
        assertThat(testCmd).contains("ollama list").contains("grep");

        assertThat(healthcheck.get("interval")).isEqualTo("15s");
        assertThat(healthcheck.get("retries")).isEqualTo(40);
        assertThat(healthcheck.get("start_period")).isEqualTo("30s");
    }

    @SuppressWarnings("unchecked")
    @Test
    void app은_postgres에는_필수로_ollama에는_선택적으로_의존한다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> app = service(compose, "app");

        Object dependsOnRaw = app.get("depends_on");
        assertThat(dependsOnRaw).isInstanceOf(Map.class);
        Map<String, Object> dependsOn = (Map<String, Object>) dependsOnRaw;

        Map<String, Object> postgresDep = (Map<String, Object>) dependsOn.get("postgres");
        assertThat(postgresDep.get("condition")).isEqualTo("service_healthy");

        Map<String, Object> ollamaDep = (Map<String, Object>) dependsOn.get("ollama");
        assertThat(ollamaDep)
                .as("ollama depends_on 배선이 없으면 회사서버(profile 미활성)에서 docker compose up app 자체가 깨질 위험을 못 막는다")
                .isNotNull();
        assertThat(ollamaDep.get("condition")).isEqualTo("service_healthy");
        assertThat(ollamaDep.get("required"))
                .as("required: false여야 profile 미활성 환경(회사 서버)에서 ollama가 없어도 app이 뜬다")
                .isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void 기본_compose에는_GPU_예약_블록이_없다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            assertThat(svc.containsKey("deploy"))
                    .as(entry.getKey() + " 서비스에 GPU 예약 블록이 있으면 nvidia-container-toolkit 없는 노트북에서"
                            + " 컨테이너 기동 자체가 실패한다 — deploy 블록은 docker-compose.gpu.yml 오버레이로만 분리")
                    .isFalse();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void gpu_오버레이는_ollama_서비스_하나에만_nvidia_예약을_추가한다() throws IOException {
        Path path = Paths.get("docker-compose.gpu.yml");
        assertThat(Files.exists(path)).as("docker-compose.gpu.yml 아직 미작성").isTrue();

        Map<String, Object> overlay;
        try (InputStream in = Files.newInputStream(path)) {
            overlay = new Yaml().load(in);
        }
        Map<String, Object> services = (Map<String, Object>) overlay.get("services");
        assertThat(services.keySet())
                .as("오버레이는 ollama 서비스 하나만 건드려야 한다 — app/postgres/chroma는 GPU와 무관")
                .containsExactly("ollama");

        Map<String, Object> ollama = (Map<String, Object>) services.get("ollama");
        Map<String, Object> deploy = (Map<String, Object>) ollama.get("deploy");
        Map<String, Object> resources = (Map<String, Object>) deploy.get("resources");
        Map<String, Object> reservations = (Map<String, Object>) resources.get("reservations");
        List<Map<String, Object>> devices = (List<Map<String, Object>>) reservations.get("devices");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).get("driver")).isEqualTo("nvidia");
        assertThat((List<Object>) devices.get(0).get("capabilities")).contains("gpu");
    }

    @Test
    void env_lite_example_템플릿은_로컬_LLM_기본값을_전부_채운다() throws IOException {
        Path path = Paths.get(".env.lite.example");
        assertThat(Files.exists(path)).as(".env.lite.example 아직 미작성").isTrue();

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }

        assertThat(props.getProperty("LLM_PROVIDER"))
                .as("이 경량 배포판은 llm.provider=local 고정 패키지여야 한다")
                .isEqualTo("local");
        assertThat(props.getProperty("LLM_LOCAL_URL")).isEqualTo("http://ollama:11434");
        assertThat(props.getProperty("LLM_LOCAL_MODEL")).isEqualTo("qwen2.5-coder:7b");
        assertThat(props.getProperty("LLM_LOCAL_API_KEY", "")).as("Ollama는 무인증이므로 비워둬야 한다").isEmpty();
        assertThat(props.getProperty("COMPOSE_PROFILES"))
                .as("사용자가 --profile 플래그를 안 외워도 되게 .env 기본값으로 llm-rag를 켜둬야 한다")
                .isEqualTo("llm-rag");
        assertThat(props.getProperty("CLAUDE_API_KEY", "")).as("local 모드에선 안 쓰이므로 비워둬야 한다").isEmpty();
    }

    @Test
    void entrypoint_스크립트는_최초_기동시에만_모델을_pull하고_serve를_포그라운드로_유지한다() throws IOException {
        Path path = Paths.get("docker/ollama-entrypoint.sh");
        assertThat(Files.exists(path)).as("docker/ollama-entrypoint.sh 아직 미작성").isTrue();

        String content = Files.readString(path);
        assertThat(content).as("serve를 백그라운드로 먼저 띄워야 pull 시도가 가능하다").contains("ollama serve");
        assertThat(content).as("최초 기동 시 모델을 자동으로 받아야 한다").contains("ollama pull");
        assertThat(content).as("재시작 시 재다운로드를 막으려면 존재 여부를 먼저 확인해야 한다").contains("ollama list");
        assertThat(content)
                .as(".env(LLM_LOCAL_MODEL)·entrypoint·healthcheck 세 곳의 기본 모델값이 어긋나면 안 된다")
                .contains("qwen2.5-coder:7b");
        assertThat(content)
                .as("compose 쪽 environment: OLLAMA_MODEL 배선과 변수명이 일치해야 한다")
                .contains("OLLAMA_MODEL");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ollama와_chroma는_외부에_노출되지_않는다() throws IOException {
        Map<String, Object> compose = loadDockerCompose();

        for (String name : List.of("ollama", "chroma")) {
            Map<String, Object> svc = service(compose, name);
            assertThat(svc).as(name + " 서비스가 아직 정의되지 않음").isNotNull();

            Object portsRaw = svc.get("ports");
            if (portsRaw != null) {
                List<String> ports = (List<String>) portsRaw;
                assertThat(ports)
                        .as(name + "는 로컬 전용 바인딩만 허용됨(127.0.0.1:... 형태) 또는 ports: 자체를 안 씀(expose:)")
                        .allMatch(p -> p.startsWith("127.0.0.1:"));
            }
        }
    }
}

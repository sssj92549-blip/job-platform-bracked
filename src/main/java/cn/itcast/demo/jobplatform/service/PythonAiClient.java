package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

/**
 * 唯一Python HTTP入口，错误统一脱敏；接口地址和内部认证从配置读取。
 */
@Service
public class PythonAiClient {
    private final RestTemplate rest;

    public PythonAiClient(RestTemplate aiRestTemplate) {
        rest = aiRestTemplate;
    }

    public JsonNode call(HttpMethod method, String path, Object body) {
        try {
            JsonNode response = rest.exchange(path, method, new HttpEntity<>(body), JsonNode.class).getBody();
            if (response == null || !response.path("code").isIntegralNumber() || response.path("code").asInt() != 0 || !response.hasNonNull("data"))
                invalid();
            return response.get("data");
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 422)
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, 42201, "AI无法识别内容或返回结构无效");
            if (status == 503) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 50301, "AI索引服务暂不可用");
            if (status == 504) throw new BusinessException(HttpStatus.GATEWAY_TIMEOUT, 50401, "AI服务处理超时");
            throw new BusinessException(HttpStatus.BAD_GATEWAY, 50201, "AI服务调用失败，请稍后重试");
        } catch (ResourceAccessException e) {
            throw new BusinessException(HttpStatus.GATEWAY_TIMEOUT, 50401, "AI连接失败或处理超时");
        } catch (RestClientException e) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, 50201, "AI服务响应无效");
        }
    }

    public static void invalid() {
        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, 42201, "AI输出不符合约定结构");
    }
}

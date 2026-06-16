package woowacourse.payment.client;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import woowacourse.payment.PaymentConfirmation;
import woowacourse.payment.PaymentGateway;
import woowacourse.payment.PaymentResult;
import woowacourse.payment.PaymentStatus;
import woowacourse.payment.client.dto.ConfirmRequest;
import woowacourse.payment.client.dto.TossErrorResponse;
import woowacourse.payment.client.dto.TossPaymentResponse;

/**
 * PaymentGateway 포트의 Toss 구현(어댑터). Toss 의 요청·응답·에러 포맷은 이 클래스 밖으로 새어 나가지 않는다(부패 방지 계층).
 */
@Component
public class TossPaymentGateway implements PaymentGateway {

    private final RestClient tossRestClient;
    private final ObjectMapper objectMapper;

    public TossPaymentGateway(RestClient tossRestClient, ObjectMapper objectMapper) {
        this.tossRestClient = tossRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentResult confirm(PaymentConfirmation confirmation) {
        // TODO: ConfirmRequest 로 /v1/payments/confirm 을 호출하고, 에러 응답은 onStatus 에서
        //   TossPaymentException.of(...) 로, 성공 응답은 PaymentResult 로 변환해 반환한다.
        TossPaymentResponse response = tossRestClient.post()
                .uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ConfirmRequest(confirmation.paymentKey(), confirmation.orderId(), confirmation.amount()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    TossErrorResponse error = objectMapper.readValue(res.getBody(), TossErrorResponse.class);
                    throw TossPaymentException.of(res.getStatusCode(), error);
                })
                .body(TossPaymentResponse.class);

        PaymentStatus paymentStatus = PaymentStatus.from(response.status());

        return new PaymentResult(response.paymentKey(), response.orderId(), paymentStatus, response.totalAmount());
    }

}

package woowacourse.payment.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import woowacourse.payment.PaymentConfirmation;
import woowacourse.payment.PaymentGateway;
import woowacourse.payment.PaymentResult;
import woowacourse.payment.PaymentStatus;
import woowacourse.payment.client.dto.ConfirmRequest;
import woowacourse.payment.client.dto.TossPaymentResponse;

/**
 * PaymentGateway 포트의 Toss 구현(어댑터). 모듈 주제가 타임아웃이라 에러코드 매핑(모듈 1)은 생략했다.
 */
@Component
public class TossPaymentGateway implements PaymentGateway {

    private final RestClient tossRestClient;

    public TossPaymentGateway(RestClient tossRestClient) {
        this.tossRestClient = tossRestClient;
    }

    @Override
    public PaymentResult confirm(PaymentConfirmation confirmation) {
        var request = new ConfirmRequest(
                confirmation.paymentKey(), confirmation.orderId(), confirmation.amount());
        var response = tossRestClient.post()
                .uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                // TODO: 재시도 시 중복 결제를 막도록 Idempotency-Key 헤더를 싣는다.
                //  키는 재시도 간 동일한 값이어야 한다(주문 식별자 등). 예: .header("Idempotency-Key", confirmation.orderId())
                //  지금은 키가 없어, 타임아웃으로 끊긴 뒤 재시도하면 서버가 매번 새 결제로 처리한다.
                .header("Idempotency-Key", confirmation.orderId())
                .body(request)
                .retrieve()
                .body(TossPaymentResponse.class);
        return new PaymentResult(
                response.paymentKey(),
                response.orderId(),
                PaymentStatus.from(response.status()),
                response.totalAmount()
        );
    }

}

package woowacourse.payment;

import org.springframework.stereotype.Service;
import woowacourse.payment.order.OrderRepository;

/**
 * 결제 승인 유스케이스. 게이트웨이 호출 '전에' 금액을 검증하는 것이 핵심이다.
 */
@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;

    public PaymentService(OrderRepository orderRepository, PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
    }

    public PaymentResult confirm(String paymentKey, String orderId, Long amount) {
        var order = orderRepository.getByOrderId(orderId);
        // TODO: 저장된 주문 금액과 요청 amount 가 다르면 PaymentAmountMismatchException 으로 게이트웨이 호출 '전에' 차단한다.
        order.validateAmount(amount);
        var confirmation = new PaymentConfirmation(paymentKey, orderId, amount);
        return paymentGateway.confirm(confirmation);
    }

}

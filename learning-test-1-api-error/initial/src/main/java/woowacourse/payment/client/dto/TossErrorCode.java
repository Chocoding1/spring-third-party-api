package woowacourse.payment.client.dto;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import woowacourse.payment.client.TossPaymentException;
import woowacourse.payment.client.TossPaymentException.AlreadyProcessed;
import woowacourse.payment.client.TossPaymentException.CardRejected;
import woowacourse.payment.client.TossPaymentException.DuplicatedOrder;
import woowacourse.payment.client.TossPaymentException.GatewayConfig;
import woowacourse.payment.client.TossPaymentException.InvalidRequest;
import woowacourse.payment.client.TossPaymentException.PaymentNotFound;
import woowacourse.payment.client.TossPaymentException.Retryable;
import woowacourse.payment.client.TossPaymentException.SessionExpired;

/**
 * Toss 에러 코드와 그에 대응하는 도메인 예외를 한곳에 묶은 매핑 테이블. 코드를 추가할 때 상수 한 줄만 늘리면 된다(switch 분기 수정 불필요).
 */
public enum TossErrorCode {

  ALREADY_PROCESSED(AlreadyProcessed::new, "ALREADY_PROCESSED_PAYMENT"),
  DUPLICATED_ORDER(DuplicatedOrder::new, "DUPLICATED_ORDER_ID"),
  SESSION_EXPIRED(SessionExpired::new, "NOT_FOUND_PAYMENT_SESSION"),
  INVALID_REQUEST(InvalidRequest::new, "INVALID_REQUEST"),
  GATEWAY_CONFIG(GatewayConfig::new, "UNAUTHORIZED_KEY", "INVALID_API_KEY"),
  CARD_REJECTED(CardRejected::new, "REJECT_CARD_PAYMENT"),
  PAYMENT_NOT_FOUND(PaymentNotFound::new, "NOT_FOUND_PAYMENT"),
  RETRYABLE(Retryable::new, "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING");

  private final Function<String, TossPaymentException> factory;
  private final List<String> codes;

  TossErrorCode(Function<String, TossPaymentException> factory, String... codes) {
    this.factory = factory;
    this.codes = List.of(codes);
  }

  /**
   * Toss 가 내려준 코드 문자열에 해당하는 상수를 찾는다. 정의되지 않은 코드면 비어 있는 Optional 을 돌려준다.
   */
  public static Optional<TossErrorCode> from(String code) {
    return Arrays.stream(values())
        .filter(errorCode -> errorCode.codes.contains(code))
        .findFirst();
  }

  public TossPaymentException toException(String message) {
    return factory.apply(message);
  }
}

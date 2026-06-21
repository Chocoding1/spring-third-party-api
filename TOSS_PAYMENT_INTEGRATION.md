# Toss Payments 결제 연동 가이드 (핸드오프 문서)

> 다른 프로젝트에 Toss Payments 결제 승인 기능을 붙이기 위한 요약. 이 repo(`spring-third-party-api`)의
> `learning-test-1-api-error`에서 학습·검증한 패턴을 정리한 것이다. 코드 스니펫은 그대로 가져다 쓸 수 있다.

## 0. 핵심 개념 한 줄 요약

- **카드 정보는 서버가 절대 만지지 않는다.** 카드 입력·인증은 브라우저의 Toss 결제창 + 카드사가 처리한다.
- 서버가 하는 일은 **인증 결과(paymentKey)로 결제를 "승인(confirm)"** 하는 것뿐이다.
- 키는 두 종류: **클라이언트 키(`test_ck_`/`test_gck_`)** 는 브라우저 위젯용, **시크릿 키(`test_sk_`/`test_gsk_`)** 는 서버 승인 전용. 역할이 다르며 같은 상점이어야 한다.

## 1. 전체 결제 흐름

```
[브라우저]                         [우리 서버]                    [Toss]
   |                                  |                            |
   |  GET /  (결제 페이지 요청)        |                            |
   |--------------------------------->|                            |
   |   주문 미리 저장(orderId, amount) |                            |
   |   clientKey/orderId/amount 내려줌 |                            |
   |<---------------------------------|                            |
   |                                  |                            |
   |  결제창 SDK로 카드 인증 (서버 미관여) ------------------------->|
   |  성공 시 successUrl로 리다이렉트                               |
   |  (paymentKey, orderId, amount 쿼리파라미터 첨부) <-------------|
   |                                  |                            |
   |  GET /payments/success?...       |                            |
   |--------------------------------->|                            |
   |        저장 주문과 amount 검증     |                            |
   |        POST /v1/payments/confirm ---------------------------->|
   |        (Basic 인증 + 3필드 JSON)                              |
   |        승인 결과 <-------------------------------------------|
   |   성공/실패 페이지 렌더링          |                            |
   |<---------------------------------|                            |
```

핵심: **서버는 결제 "전에" 주문(orderId→amount)을 미리 저장**해 둔다. successUrl로 돌아온 amount가
위변조됐는지 대조하기 위함이다. 검증은 반드시 Toss confirm API 호출 **전에** 한다.

## 2. 아키텍처 (포트-어댑터 / 부패 방지 계층)

```
PaymentService (도메인 유스케이스)
   └─ depends on → PaymentGateway (포트, interface)
                      └─ implemented by → TossPaymentGateway (어댑터)
                                              └─ RestClient → Toss API
```

- 도메인은 `PaymentGateway` 인터페이스만 안다. 어떤 PG사인지 모른다.
- Toss의 요청/응답/에러 포맷은 `TossPaymentGateway`(어댑터) **밖으로 새어나가지 않는다.** 들어올 때
  도메인 모델 → Toss DTO, 나갈 때 Toss DTO → 도메인 모델로 번역한다.

### 도메인 모델 ↔ Toss DTO

| 도메인 (PG 비종속) | Toss DTO (외부 스펙) |
|---|---|
| `PaymentConfirmation(paymentKey, orderId, amount)` | `ConfirmRequest(paymentKey, orderId, amount)` (요청 바디) |
| `PaymentResult(paymentKey, orderId, status, approvedAmount)` | `TossPaymentResponse(... totalAmount ...)` (성공 응답) |
| `PaymentStatus` (enum) | `status` 문자열 ("DONE" 등) → `PaymentStatus.from()` 으로 변환, 미정의값은 `UNKNOWN` |
| `TossPaymentException` (+ 중첩 예외) | `TossErrorResponse(code, message)` (에러 응답) |

## 3. 설정: RestClient 빈 (Basic 인증 + 시크릿 키 외부화)

```java
@Configuration
public class TossClientConfig {

  @Bean
  public RestClient tossRestClient(
      @Value("${toss.base-url}") String baseUrl,
      @Value("${toss.secret-key}") String secretKey
  ) {
    // Basic 인증: base64(시크릿키 + ":")  — 콜론 뒤 비밀번호는 비움, 인코딩 시 UTF-8 명시
    var basic = Base64.getEncoder()
        .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    return RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)  // 모든 요청에 자동 첨부
        .build();
  }
}
```

포인트:
- baseUrl과 Authorization 헤더를 빈에 박아두면, 실제 호출부에서는 **경로(path)만** 쓰면 되고 인증은 신경 쓸 필요 없다.
- 시크릿 키는 **하드코딩 금지** → `application.yaml`로 외부화하고 `@Value`로 주입.

### application.yaml

```yaml
toss:
  base-url: https://api.tosspayments.com
  # 공개 문서 테스트 키 쌍 (client-key: 브라우저 위젯용 / secret-key: 서버 승인용, 같은 상점)
  client-key: test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm
  secret-key: test_gsk_docs_OaPz8L5KdmQXkzRz3y47BMw6
```

> 실서비스에서는 시크릿 키를 yaml에 평문으로 두지 말고 환경변수/시크릿 매니저로 주입할 것.

## 4. 결제 승인 호출 + 에러 처리 (RestClient + onStatus)

```java
@Component
public class TossPaymentGateway implements PaymentGateway {

  private final RestClient tossRestClient;
  private final ObjectMapper objectMapper;   // 에러 바디 직접 파싱용 (Jackson 3: tools.jackson.databind.ObjectMapper)

  // 생성자 주입 생략 ...

  @Override
  public PaymentResult confirm(PaymentConfirmation confirmation) {
    var request = new ConfirmRequest(
        confirmation.paymentKey(), confirmation.orderId(), confirmation.amount());

    TossPaymentResponse response = tossRestClient.post()
        .uri("/v1/payments/confirm")                       // baseUrl 뒤 경로만
        .contentType(MediaType.APPLICATION_JSON)           // 요구사항: Content-Type application/json
        .body(request)                                     // 3필드 JSON 자동 직렬화
        .retrieve()
        .onStatus(HttpStatusCode::isError, (req, res) -> { // 4xx/5xx 가로채기
          var error = objectMapper.readValue(res.getBody(), TossErrorResponse.class);
          throw TossPaymentException.of(res.getStatusCode(), error);  // ★ 반드시 throw
        })
        .body(TossPaymentResponse.class);                  // 성공 시 응답 → DTO

    return new PaymentResult(
        response.paymentKey(),
        response.orderId(),
        PaymentStatus.from(response.status()),
        response.totalAmount()                             // Toss totalAmount → 도메인 approvedAmount
    );
  }
}
```

### ⚠️ 함정 (실제로 겪은 것들)

1. **onStatus 핸들러에서 반드시 `throw` 할 것.** `TossPaymentException.of(...)`는 예외를 *return*만 한다.
   `throw`를 빠뜨리면 핸들러가 정상 종료되고 뒤의 `.body()`가 에러 바디를 성공 DTO로 파싱하려다 깨진다.
2. **`HttpStatusCode::isError` (O) / `HttpStatus::isError` (X).** `onStatus`의 조건은 `Predicate<HttpStatusCode>`다.
   `HttpStatus::isError`는 `Predicate<HttpStatus>`가 되어 타입 불일치(반공변)로 컴파일 에러가 난다.
   import도 `org.springframework.http.HttpStatusCode`.
3. **에러 바디는 자동 역직렬화가 안 된다.** `res.getBody()`는 raw InputStream이라 `objectMapper`로 직접 파싱한다.
   그래서 어댑터에 `ObjectMapper`를 주입받는다.

## 5. 에러 코드 → 도메인 예외 매핑 (enum 방식)

Toss 에러 응답(`{code, message}`)의 `code`별로 도메인 예외를 매핑한다. switch로도 되지만, 이 repo에서는
**enum 매핑 테이블** 방식을 채택했다(코드 추가 시 상수 한 줄만 늘리면 됨).

```java
public enum TossErrorCode {
  ALREADY_PROCESSED(AlreadyProcessed::new, "ALREADY_PROCESSED_PAYMENT"),
  DUPLICATED_ORDER(DuplicatedOrder::new, "DUPLICATED_ORDER_ID"),
  SESSION_EXPIRED(SessionExpired::new, "NOT_FOUND_PAYMENT_SESSION"),
  INVALID_REQUEST(InvalidRequest::new, "INVALID_REQUEST"),
  GATEWAY_CONFIG(GatewayConfig::new, "UNAUTHORIZED_KEY", "INVALID_API_KEY"),  // 코드 여러 개 → 한 예외
  CARD_REJECTED(CardRejected::new, "REJECT_CARD_PAYMENT"),
  PAYMENT_NOT_FOUND(PaymentNotFound::new, "NOT_FOUND_PAYMENT"),
  RETRYABLE(Retryable::new, "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING");

  private final Function<String, TossPaymentException> factory;  // 생성자 참조
  private final List<String> codes;

  TossErrorCode(Function<String, TossPaymentException> factory, String... codes) {
    this.factory = factory;
    this.codes = List.of(codes);
  }

  public static Optional<TossErrorCode> from(String code) {
    return Arrays.stream(values())
        .filter(errorCode -> errorCode.codes.contains(code))
        .findFirst();
  }

  public TossPaymentException toException(String message) {
    return factory.apply(message);
  }
}
```

```java
// TossPaymentException.of()
public static TossPaymentException of(HttpStatusCode status, TossErrorResponse error) {
  return TossErrorCode.from(error.code())
      .map(errorCode -> errorCode.toException(error.message()))
      .orElseGet(() -> new TossPaymentException(status, error.code(), error.message()));  // 미정의 코드 fallback
}
```

`TossPaymentException`은 상황별 대응을 위해 **중첩 예외 클래스**(`AlreadyProcessed`, `CardRejected`,
`GatewayConfig`, `Retryable` 등)를 품는다. 모두 `TossPaymentException`을 상속하므로 호출부에서
자식 타입으로 세분화해 잡거나 부모 타입으로 한 번에 잡을 수 있다.

| code | 예외 | HTTP | 의미 / 대응 |
|---|---|---|---|
| `ALREADY_PROCESSED_PAYMENT` | AlreadyProcessed | 400 | 중복 승인 시도 |
| `DUPLICATED_ORDER_ID` | DuplicatedOrder | 400 | 중복 주문번호 |
| `NOT_FOUND_PAYMENT_SESSION` | SessionExpired | 400 | 결제 세션 만료 |
| `INVALID_REQUEST` | InvalidRequest | 400 | 형식 오류/필수값 누락 |
| `UNAUTHORIZED_KEY`, `INVALID_API_KEY` | GatewayConfig | 401 | 키 설정 오류 → 운영 알람 대상 |
| `REJECT_CARD_PAYMENT` | CardRejected | 403 | 한도초과/잔액부족 |
| `NOT_FOUND_PAYMENT` | PaymentNotFound | 404 | 존재하지 않는 결제 |
| `FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING` | Retryable | 500 | 토스 내부 오류 → **재시도 대상** |
| (그 외) | TossPaymentException | - | 기본 예외 |

> 패키지 배치 주의: 이 repo에선 `TossErrorCode`를 dto 패키지에 뒀는데, 그러면 dto ↔ client가 서로 의존하는
> 순환이 생긴다. 동작엔 문제없으나, 새 프로젝트에선 `TossErrorCode`를 예외와 같은 패키지(client)에 두는 편이
> 의존 방향이 깔끔하다.

## 6. 서버 엔드포인트 (SSR, Thymeleaf 기준)

```java
@GetMapping("/")              // 결제 페이지: 주문 미리 저장 + clientKey/orderId/orderName/amount 내려줌
@GetMapping("/payments/success")  // successUrl: amount 검증 → paymentService.confirm() → 성공/실패 뷰
@GetMapping("/payments/fail")     // failUrl: code/message로 실패 뷰 (사용자 취소 시 orderId 없을 수 있음)
```

`PaymentService.confirm()`은 confirm 호출 전에 금액을 먼저 검증한다:

```java
public PaymentResult confirm(String paymentKey, String orderId, Long amount) {
  var order = orderRepository.getByOrderId(orderId);
  order.validateAmount(amount);                  // ★ 게이트웨이 호출 '전에' 위변조 차단
  return paymentGateway.confirm(new PaymentConfirmation(paymentKey, orderId, amount));
}
```

> REST API(비 SSR) 프로젝트라면: 결제 페이지/주문 저장은 그대로, success/fail은 프론트가 받아 서버의
> `POST /payments/confirm` 같은 엔드포인트로 paymentKey/orderId/amount를 넘기는 형태로 바꾸면 된다.
> 서버 confirm 로직(검증 → 게이트웨이 호출)은 동일.

## 7. 클라이언트(브라우저) 결제창 — 참고용

서버가 만질 필요 없는 영역. v2 결제위젯(주문서형) SDK 기준 핵심만:

```html
<script src="https://js.tosspayments.com/v2/standard"></script>
<div id="payment-method"></div>
<div id="agreement"></div>
<button id="payButton" disabled>결제하기</button>
<script>
  const tossPayments = TossPayments(clientKey);                       // 클라이언트 키
  const widgets = tossPayments.widgets({ customerKey: TossPayments.ANONYMOUS });
  await widgets.setAmount({ currency: "KRW", value: amount });        // 금액 먼저 설정해야 렌더 가능
  await Promise.all([
    widgets.renderPaymentMethods({ selector: "#payment-method", variantKey: "DEFAULT" }),
    widgets.renderAgreement({ selector: "#agreement", variantKey: "AGREEMENT" }),
  ]);
  // 결제하기 클릭 시
  await widgets.requestPayment({
    orderId, orderName,
    successUrl: window.location.origin + "/payments/success",
    failUrl: window.location.origin + "/payments/fail",
  });
  // 사용자가 창을 닫으면 failUrl 리다이렉트가 아니라 USER_CANCEL 에러가 throw 됨
</script>
```

## 8. 의존성 / 환경

- Java 21, Spring Boot **4.0.6**, `spring-boot-starter-webmvc`(+ thymeleaf SSR이면 starter-thymeleaf)
- HTTP 클라이언트: **`RestClient`** (Spring 6.1+ 동기 클라이언트)
- JSON: **Jackson 3** — import 경로가 `tools.jackson.databind.ObjectMapper` (구 `com.fasterxml.jackson` 아님).
  단, 애너테이션 `@JsonIgnoreProperties`는 여전히 `com.fasterxml.jackson.annotation`.
- DTO에는 `@JsonIgnoreProperties(ignoreUnknown = true)`를 붙여 모르는 응답 필드를 무시한다.

## 9. 테스트 전략

`MockWebServer`(okhttp3)로 Toss를 흉내내 상태코드/에러코드/지연을 주입한다.

```java
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

```java
@DynamicPropertySource
static void tossProperties(DynamicPropertyRegistry registry) {
  registry.add("toss.base-url", () -> mockWebServer.url("/").toString());  // baseUrl을 목서버로 교체
  registry.add("toss.secret-key", () -> "test_gsk_dummy");
}
```

- 성공: 200 + 정상 바디 → `PaymentResult` 매핑 검증
- 실패: 4xx/5xx + `{code, message}` → 매핑된 중첩 예외가 던져지는지 `@ParameterizedTest`로 검증

## 10. 참고 (이 repo의 후속 학습 주제)

- `learning-test-2-timeout`: 타임아웃 설정 + **멱등성**(Idempotency-Key)으로 중복 승인 방지
- `learning-test-3-ratelimit`: rate limit 대응(재시도/백오프)

---
원본 구현: `learning-test-1-api-error/initial`(연습) 및 `/complete`(참고 답안) 의
`woowacourse/payment/**` 패키지.

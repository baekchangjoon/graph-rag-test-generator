package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * float inter-field 벤치마크(작업 #4): 두 float 입력 필드의 순수 선형 가드.
 * {@code base*2 + surcharge*3} 이 좁은 band [99.5, 100.5] 안에 들어야 201.
 *
 * <p>계수는 상수×변수 2개라 선형이고(변수×변수 비선형 회피), 한 필드만 바꾸는 generic 변이
 * (0/-1/large/null)로는 두 필드를 동시에 band로 몰 수 없다 — ConcolicOracle의 Real solveTuple이
 * 푼 (base, surcharge) 튜플만 201을 연다. band는 단측 부등식이 happy 기본값/large 변이로 우연
 * 충족되는 것을 막고(양측으로 가둠), 동시에 solver 출력의 float 반올림 오차(~1e-5)를 흡수한다
 * (정수 booking 벤치마크의 == 등식이 float에선 취약한 점을 보완).
 */
@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    public record QuoteRequest(float base, float surcharge) {
    }

    public record QuoteResponse(String result) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteResponse quote(@RequestBody QuoteRequest req) {
        float combined = req.base() * 2.0f + req.surcharge() * 3.0f;
        if (combined < 99.5f) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "base*2 + surcharge*3 must be at least 99.5");
        }
        if (combined > 100.5f) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "base*2 + surcharge*3 must be at most 100.5");
        }
        return new QuoteResponse("quoted");
    }
}

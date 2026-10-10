package com.stockpredict.e2e;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * End-to-end journeys through the gateway (plan A8). Requires a running stack:
 * gateway + auth + prediction + ranking (+ paper trading for the watchlist
 * checks). Public journeys run without credentials; authenticated ones run
 * when {@code -Pe2eToken=<access token>} is supplied. The full
 * register/verify/login journey additionally needs the verification token,
 * supplied via {@code -Pe2eVerifyToken=<token>} or {@code E2E_VERIFY_TOKEN};
 * without it the verification-dependent steps are skipped (assumption).
 */
@EnabledIfSystemProperty(named = "e2e.baseUrl", matches = ".+")
class ApiJourneysE2ETest {

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = System.getProperty("e2e.baseUrl");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void jwksIsPublicAndServesRsaKeys() {
        given()
                .when().get("/api/v1/.well-known/jwks.json")
                .then().statusCode(200)
                .body("keys", hasSize(greaterThan(0)))
                .body("keys[0].kty", equalTo("RSA"));
    }

    @Test
    void registerThenUnverifiedLoginIsRejected() {
        String email = "e2e-" + UUID.randomUUID() + "@example.com";
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", "e2e-password-" + UUID.randomUUID()))
                .when().post("/api/v1/auth/register")
                .then().statusCode(201)
                .body("email", equalTo(email))
                .body("verificationRequired", equalTo(true));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", "e2e-password-" + UUID.randomUUID()))
                .when().post("/api/v1/auth/login")
                .then().statusCode(401); // wrong password on an unverified account
    }

    @Test
    void registerVerifyThenLoginJourney() {
        String email = "e2e-journey-" + UUID.randomUUID() + "@example.com";
        String password = "e2e-password-" + UUID.randomUUID();

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .when().post("/api/v1/auth/register")
                .then().statusCode(201)
                .body("email", equalTo(email))
                .body("verificationRequired", equalTo(true));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .when().post("/api/v1/auth/login")
                .then().statusCode(403)
                .body("code", equalTo("EMAIL_NOT_VERIFIED"));

        String verifyToken = verifyToken();
        Assumptions.assumeTrue(verifyToken != null && !verifyToken.isBlank(),
                "No e2e verification token: pass -Pe2eVerifyToken=<token> or set E2E_VERIFY_TOKEN "
                        + "to complete register -> verify -> login -> refresh -> logout. "
                        + "Register and the EMAIL_NOT_VERIFIED login rejection were still exercised.");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("token", verifyToken))
                .when().post("/api/v1/auth/verify-email")
                .then().statusCode(200);

        String refreshToken = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .body("accessToken", notNullValue())
                .extract().path("refreshToken");

        String rotatedRefreshToken = given()
                .contentType(ContentType.JSON)
                .body(Map.of("refreshToken", refreshToken))
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(200)
                .body("accessToken", notNullValue())
                .extract().path("refreshToken");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("refreshToken", rotatedRefreshToken))
                .when().post("/api/v1/auth/logout")
                .then().statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("refreshToken", rotatedRefreshToken))
                .when().post("/api/v1/auth/refresh")
                .then().statusCode(401);
    }

    private static String verifyToken() {
        String token = System.getProperty("e2e.verifyToken");
        if (token == null || token.isBlank()) {
            token = System.getenv("E2E_VERIFY_TOKEN");
        }
        return token;
    }

    @Test
    void protectedEndpointsRejectAnonymousRequests() {
        given().when().get("/api/v1/predictions/intraday")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
        given().when().get("/api/v1/recommendations/long-term")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
        given().when().get("/api/v1/paper/portfolio")
                .then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.token", matches = ".+")
    void predictionsAndRankingCarryModelMetadata() {
        String token = System.getProperty("e2e.token");

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/v1/predictions/intraday?limit=10")
                .then().statusCode(200)
                .body("modelVersion", notNullValue())
                .body("disclaimer", notNullValue())
                .body("predictions", notNullValue());

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/v1/recommendations/long-term?limit=10")
                .then().statusCode(200)
                .body("picks.size()", lessThanOrEqualTo(10));
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.token", matches = ".+")
    void paperTradingReconcilesPortfolio() {
        String token = System.getProperty("e2e.token");

        given().header("Authorization", "Bearer " + token)
                .when().post("/api/v1/paper/account")
                .then().statusCode(201)
                .body("cash", notNullValue());

        given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("symbols", java.util.List.of("AAPL", "MSFT")))
                .when().put("/api/v1/paper/watchlist")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/v1/paper/portfolio")
                .then().statusCode(200)
                .body("equity", notNullValue())
                .body("cash", notNullValue());
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.token", matches = ".+")
    void freePlanWatchlistLimitIsEnforcedServerSide() {
        String token = System.getProperty("e2e.token");
        String[] symbols = {"AAPL", "MSFT", "NVDA", "AMZN", "GOOGL",
                "META", "TSLA", "JPM", "XOM", "LLY", "AVGO"};

        given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("symbols", symbols))
                .when().put("/api/v1/paper/watchlist")
                .then().statusCode(403)
                .body("code", containsString("PLAN_LIMIT"));
    }
}

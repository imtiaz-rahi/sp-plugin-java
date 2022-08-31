package com.shurjopay.plugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shurjopay.plugin.model.PaymentReq;
import com.shurjopay.plugin.model.PaymentRes;
import com.shurjopay.plugin.model.ShurjoPayToken;
import com.shurjopay.plugin.model.VerifiedOrder;

/**
 * 
 * Plug-in service to provide shurjoPay get way services.
 * 
 * @author Al - Amin
 * @since 2022-06-13
 */
public class ShurjoPayPlugin {
	private Logger logger = Logger.getLogger(ShurjoPayPlugin.class.getName());

	private ShurjoPayToken authToken;
	private PropertiesReader reader = PropertiesReader.instance();

	/**
	 * Return authorization token for shurjoPay payment gateway system. Setup
	 * shurjopay.properties file .
	 * 
	 * @return authentication details with valid token
	 * @throws IllegalAccessException
	 * @throws @{@link IllegalAccessException}
	 */
	private ShurjoPayToken authenticate() throws IllegalAccessException {
		Map<String, String> tokenReq = new HashMap<>();
		tokenReq.put("username", getProperty("username"));
		tokenReq.put("password", getProperty("password"));

		try {
			HttpClient client = getClient();
			String requestBody = prepareReqBody(tokenReq);
			HttpRequest request = postRequest(requestBody, EndPoints.TOKEN.getValue());
			HttpResponse<Supplier<ShurjoPayToken>> response = client.send(request,
					new JsonBodyHandler<>(ShurjoPayToken.class));
			authToken = response.body().get();

		} catch (IOException | InterruptedException e) {
			logger.log(Level.SEVERE, "Invalid User name or Password due to shurjoPay authentication.");
		}

		if (authToken.getMessage().equals("Ok")) {
			logger.log(Level.INFO, "Authentication token has been generated successfully.");
		} else {
			throw new IllegalAccessException("Invalid User name or Password due to shurjoPay authentication.");
		}
		return authToken;
	}

	/**
	 * 
	 * This method is used for making payment.
	 * 
	 * @param Payment request object. See the shurjoPay version-2 integration
	 *                documentation(beta).docx for details.
	 * @return Payment response object contains redirect URL to reach payment page,
	 *         order id to verify order in shurjoPay.
	 */
	public PaymentRes makePayment(PaymentReq req) {
		try {
			if (Objects.isNull(authToken))
				authToken = authenticate();
			
			if (isTokenExpired(authToken))
				authToken = authenticate();
			
			HttpClient client = getClient();
			String callBackUrl = getProperty("callback-url");
			req.setReturnUrl(callBackUrl);
			req.setCancelUrl(callBackUrl);
			req.setAuthToken(authToken.getToken());
			req.setStoreId(authToken.getStoreId());

			String requestBody = prepareReqBody(req);
			HttpRequest request = postRequest(requestBody, EndPoints.MAKE_PMNT.getValue());
			HttpResponse<Supplier<PaymentRes>> response = client.send(request, new JsonBodyHandler<>(PaymentRes.class));
			return response.body().get();
		} catch (IOException | InterruptedException | IllegalAccessException e) {
			logger.log(Level.SEVERE, "Payment request failed", e.getCause());
			return null;
		}
	}

	/**
	 * 
	 * This method is used for verifying order by order id which could be get by
	 * Payment response object
	 * 
	 * @param orderId
	 * @return order object if order verified successfully
	 */
	public VerifiedOrder verifyOrder(String orderId) {
		try {
			if (Objects.isNull(authToken))
				authToken = authenticate();
			
			if (isTokenExpired(authToken))
				authToken = authenticate();
			
			HttpClient client = getClient();
			Map<String, String> orderMap = new HashMap<>();
			orderMap.put("order_id", orderId);

			String requestBody = prepareReqBody(orderMap);
			HttpRequest request = postRequest(requestBody, EndPoints.VERIFIED_ORDER.getValue(), true);
			HttpResponse<Supplier<VerifiedOrder[]>> response = client.send(request,
					new JsonBodyHandler<>(VerifiedOrder[].class));
			return response.body().get()[0];
		} catch (IOException | InterruptedException | IllegalAccessException e) {
			logger.log(Level.SEVERE, "Payment verification failed", e.getCause());
			return null;
		}
	}

	/**
	 * 
	 * This method is used for checking successfully paid order status by order id
	 * which could be get after verifying order
	 * 
	 * @param orderId
	 * @return order object if order verified successfully
	 */
	public VerifiedOrder checkPaymentStatus(String orderId) {
		try {
			if (Objects.isNull(authToken))
				authToken = authenticate();
			
			if (isTokenExpired(authToken))
				authToken = authenticate();
			
			HttpClient client = getClient();
			Map<String, String> orderMap = new HashMap<String, String>();
			orderMap.put("order_id", orderId);
			String requestBody = prepareReqBody(orderMap);
			HttpRequest request = postRequest(requestBody, EndPoints.PMNT_STAT.getValue(), true);

			HttpResponse<Supplier<VerifiedOrder[]>> response = client.send(request,
					new JsonBodyHandler<>(VerifiedOrder[].class));

			return response.body().get()[0];
		} catch (IOException | InterruptedException | IllegalAccessException e) {
			logger.log(Level.SEVERE, "A successful Payment verification got the payment status", e.getCause());
			return null;
		}
	}

	private HttpClient getClient() {
		return HttpClient.newHttpClient();
	}

	private String prepareReqBody(Object object) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			return mapper.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Object mapping failed due to mapping PaymentReq. Please check!", e.getCause());
		}
	}

	/**
	 * Checking expiration of token
	 * 
	 * @param {@link ShurjoPayPlugin}
	 * @return true if token is expired, otherwise return false
	 */
	private boolean isTokenExpired(ShurjoPayToken authOb) {
		DateTimeFormatter format = new DateTimeFormatterBuilder().parseCaseInsensitive()
				.appendPattern("yyyy-MM-dd hh:mm:ssa").toFormatter(Locale.US);

		LocalDateTime createdAt = LocalDateTime.parse(authOb.getTokenCreateTime(), format);
		int diff = (int) ChronoUnit.SECONDS.between(createdAt, LocalDateTime.now());
		if (authOb.getExpiresIn() <= diff)
			return true;
		return false;
	}

	private HttpRequest postRequest(String httpBody, String endPoint) {
		return HttpRequest.newBuilder(URI.create(getProperty("shurjopay-api").concat(endPoint)))
				.POST(HttpRequest.BodyPublishers.ofString(httpBody)).header("Content-Type", "application/json").build();
	}

	private HttpRequest postRequest(String httpBody, String endPoint, boolean isAuthHead) {
		return HttpRequest.newBuilder(URI.create(getProperty("shurjopay-api").concat(endPoint)))
				.header("Authorization", getFormattedToken(authToken.getToken(), authToken.getTokenType()))
				.POST(HttpRequest.BodyPublishers.ofString(httpBody)).header("Content-Type", "application/json").build();
	}

	private String getFormattedToken(String token, String tokenType) {
		return tokenType.concat(" ").concat(token);
	}

	private String getProperty(String key) {
		Properties spProps = reader.getProperties();
		String propertyValue = spProps.getProperty(key);
		if (Objects.isNull(propertyValue)) {
			logger.log(Level.SEVERE, key + " value shouldn't be empty");
			return null;
		}
		return propertyValue;
	}
}

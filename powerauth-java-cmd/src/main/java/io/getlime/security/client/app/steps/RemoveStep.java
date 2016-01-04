package io.getlime.security.client.app.steps;

import java.io.Console;
import java.io.FileWriter;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.SecretKey;

import org.json.simple.JSONObject;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;

import io.getlime.rest.api.model.ActivationRemoveRequest;
import io.getlime.rest.api.model.ActivationRemoveResponse;
import io.getlime.rest.api.model.PowerAuthAPIRequest;
import io.getlime.rest.api.model.PowerAuthAPIResponse;
import io.getlime.security.client.app.util.EncryptedStorageUtil;
import io.getlime.security.client.app.util.RestTemplateFactory;
import io.getlime.security.powerauth.client.signature.PowerAuthClientSignature;
import io.getlime.security.powerauth.lib.enums.PowerAuthSignatureTypes;
import io.getlime.security.powerauth.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.lib.util.KeyConversionUtils;
import io.getlime.security.powerauth.lib.util.http.PowerAuthHttpBody;
import io.getlime.security.powerauth.lib.util.http.PowerAuthHttpHeader;

public class RemoveStep {
	
	private static final KeyConversionUtils keyConversion = new KeyConversionUtils();
	private static final KeyGenerator keyGenerator = new KeyGenerator();
	private static final PowerAuthClientSignature signature = new PowerAuthClientSignature();
	private static final ObjectMapper mapper = new ObjectMapper();
	
	@SuppressWarnings("unchecked")
	public static JSONObject execute(Map<String, Object> context) throws Exception {
		
		// Read properties from "context"
		String uriString = (String) context.get("URI_STRING");
		JSONObject resultStatusObject = (JSONObject) context.get("STATUS_OBJECT");
		String statusFileName = (String)context.get("STATUS_FILENAME");
		String applicationId = (String)context.get("APPLICATION_ID");
		String applicationSecret = (String)context.get("APPLICATION_SECRET");
		String passwordProvided = (String)context.get("PASSWORD");
		
		System.out.println("### PowerAuth 2.0 Client Activation Removal Started");
		System.out.println();

		// Prepare the activation URI
		String fullURIString = uriString + "/pa/activation/remove";
		URI uri = new URI(fullURIString);

		// Get data from status
		String activationId = (String) resultStatusObject.get("activationId");
		long counter = (long) resultStatusObject.get("counter");
		byte[] signaturePossessionKeyBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("signaturePossessionKey"));
		byte[] signatureKnowledgeKeySalt = BaseEncoding.base64().decode((String) resultStatusObject.get("signatureKnowledgeKeySalt"));
		byte[] signatureKnowledgeKeyEncryptedBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("signatureKnowledgeKeyEncrypted"));

		// Ask for the password to unlock knowledge factor key
		char[] password = null;
		if (passwordProvided == null) {
			Console console = System.console();
			password = console.readPassword("Enter your password to unlock the knowledge related key: ");
		} else {
			password = passwordProvided.toCharArray();
		}

		// Get the signature keys
		SecretKey signaturePossessionKey = keyConversion.convertBytesToSharedSecretKey(signaturePossessionKeyBytes);
		SecretKey signatureKnowledgeKey = EncryptedStorageUtil.getSignatureKnowledgeKey(password, signatureKnowledgeKeyEncryptedBytes, signatureKnowledgeKeySalt, keyGenerator);

		// Generate nonce
		String pa_nonce = BaseEncoding.base64().encode(keyGenerator.generateRandomBytes(16));

		// Compute the current PowerAuth 2.0 signature for possession
		// and
		// knowledge factor
		String signatureBaseString = PowerAuthHttpBody.getSignatureBaseString("POST", "/pa/activation/remove", applicationSecret, pa_nonce, null);
		String pa_signature = signature.signatureForData(signatureBaseString.getBytes("UTF-8"), Arrays.asList(signaturePossessionKey, signatureKnowledgeKey), counter);
		String httpAuhtorizationHeader = PowerAuthHttpHeader.getPowerAuthSignatureHTTPHeader(activationId, applicationId, pa_nonce, PowerAuthSignatureTypes.POSSESSION_KNOWLEDGE, pa_signature, "2.0");
		System.out.println("Coomputed X-PowerAuth-Authorization header: " + httpAuhtorizationHeader);
		System.out.println();

		// Increment the counter
		counter += 1;
		resultStatusObject.put("counter", new Long(counter));

		// Store the activation status (updated counter)
		String formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
		try (FileWriter file = new FileWriter(statusFileName)) {
			file.write(formatted);
		}

		// Prepare HTTP headers
		MultiValueMap<String, String> headers = new HttpHeaders();
		headers.add("X-PowerAuth-Authorization", httpAuhtorizationHeader);

		// Send the activation status request to the server
		ActivationRemoveRequest requestObject = new ActivationRemoveRequest();
		PowerAuthAPIRequest<ActivationRemoveRequest> body = new PowerAuthAPIRequest<>();
		body.setRequestObject(requestObject);
		RequestEntity<PowerAuthAPIRequest<ActivationRemoveRequest>> request = new RequestEntity<PowerAuthAPIRequest<ActivationRemoveRequest>>(body, headers, HttpMethod.POST, uri);
		
		RestTemplate template = RestTemplateFactory.defaultRestTemplate();

		// Call the server with activation data
		System.out.println("Calling PowerAuth 2.0 Standard RESTful API at " + fullURIString + " ...");
		try {
			ResponseEntity<PowerAuthAPIResponse<ActivationRemoveResponse>> response = template.exchange(request, new ParameterizedTypeReference<PowerAuthAPIResponse<ActivationRemoveResponse>>() {
			});
			System.out.println("Done.");
			System.out.println();

			// Process the server response
			ActivationRemoveResponse responseObject = response.getBody().getResponseObject();
			String activationIdResponse = responseObject.getActivationId();

			// Print the results
			System.out.println("Activation ID: " + activationId);
			System.out.println("Server Activation ID: " + activationIdResponse);
			System.out.println();
			System.out.println("Activation remove complete.");
			System.out.println("### Done.");
			System.out.println();
			return resultStatusObject;
		} catch (HttpClientErrorException exception) {
			String responseString = exception.getResponseBodyAsString();
			try {
				Map<String, Object> errorMap = mapper.readValue(responseString, Map.class);
				System.out.println(((Map<String, Object>) errorMap.get("error")).get("message"));
			} catch (Exception e) {
				System.out.println("Service error - HTTP " + exception.getStatusCode().toString() + ": " + exception.getStatusText());
			}
			System.out.println();
			System.out.println("### Failed.");
			System.out.println();
			System.exit(1);
		} catch (ResourceAccessException exception) {
			System.out.println("Connection error - connection refused");
			System.out.println();
			System.out.println("### Failed.");
			System.out.println();
			System.exit(1);
		} catch (Exception exception) {
			System.out.println("Unknown error - " + exception.getLocalizedMessage());
			System.out.println();
			System.out.println("### Failed.");
			System.out.println();
			System.exit(1);
		}
		return null;
	}

}
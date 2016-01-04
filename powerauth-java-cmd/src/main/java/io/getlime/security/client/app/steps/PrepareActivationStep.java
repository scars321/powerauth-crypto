package io.getlime.security.client.app.steps;

import java.io.Console;
import java.io.FileWriter;
import java.net.URI;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;

import javax.crypto.SecretKey;

import org.json.simple.JSONObject;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;

import io.getlime.rest.api.model.ActivationCreateRequest;
import io.getlime.rest.api.model.ActivationCreateResponse;
import io.getlime.rest.api.model.PowerAuthAPIRequest;
import io.getlime.rest.api.model.PowerAuthAPIResponse;
import io.getlime.security.client.app.util.EncryptedStorageUtil;
import io.getlime.security.client.app.util.RestTemplateFactory;
import io.getlime.security.powerauth.client.activation.PowerAuthClientActivation;
import io.getlime.security.powerauth.client.keyfactory.PowerAuthClientKeyFactory;
import io.getlime.security.powerauth.client.vault.PowerAuthClientVault;
import io.getlime.security.powerauth.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.lib.util.KeyConversionUtils;

public class PrepareActivationStep {

	private static final PowerAuthClientActivation activation = new PowerAuthClientActivation();
	private static final KeyConversionUtils keyConversion = new KeyConversionUtils();
	private static final PowerAuthClientKeyFactory keyFactory = new PowerAuthClientKeyFactory();
	private static final KeyGenerator keyGenerator = new KeyGenerator();
	private static final PowerAuthClientVault vault = new PowerAuthClientVault();
	private static final ObjectMapper mapper = new ObjectMapper();

	@SuppressWarnings("unchecked")
	public static JSONObject execute(Map<String, Object> context) throws Exception {

		// Read properties from "context"
		String uriString = (String) context.get("URI_STRING");
		PublicKey masterPublicKey = (PublicKey) context.get("MASTER_PUBLIC_KEY");
		String activationCode = (String) context.get("ACTIVATION_CODE");
		JSONObject resultStatusObject = (JSONObject) context.get("STATUS_OBJECT");
		String statusFileName = (String)context.get("STATUS_FILENAME");
		String passwordProvided = (String)context.get("PASSWORD");

		System.out.println("### PowerAuth 2.0 Client Activation Started");
		System.out.println();

		// Prepare the activation URI
		String fullURIString = uriString + "/pa/activation/create";
		URI uri = new URI(fullURIString);

		// Fetch and parse the activation code
		String activationIdShort = activationCode.substring(0, 11);
		String activationOTP = activationCode.substring(12, 23);

		System.out.println("Activation ID Short: " + activationIdShort);
		System.out.println("Activation OTP: " + activationOTP);

		// Generate device key pair and encrypt the device public key
		KeyPair deviceKeyPair = activation.generateDeviceKeyPair();
		byte[] nonceDeviceBytes = activation.generateActivationNonce();
		byte[] cDevicePublicKeyBytes = activation.encryptDevicePublicKey(deviceKeyPair.getPublic(), activationOTP, activationIdShort, nonceDeviceBytes);

		// Prepare the server request
		ActivationCreateRequest requestObject = new ActivationCreateRequest();
		requestObject.setActivationIdShort(activationIdShort);
		requestObject.setActivationName("PowerAuth 2.0 Reference Client");
		requestObject.setActivationNonce(BaseEncoding.base64().encode(nonceDeviceBytes));
		requestObject.setcDevicePublicKey(BaseEncoding.base64().encode(cDevicePublicKeyBytes));
		PowerAuthAPIRequest<ActivationCreateRequest> body = new PowerAuthAPIRequest<>();
		body.setRequestObject(requestObject);
		RequestEntity<PowerAuthAPIRequest<ActivationCreateRequest>> request = new RequestEntity<PowerAuthAPIRequest<ActivationCreateRequest>>(body, HttpMethod.POST, uri);

		RestTemplate template = RestTemplateFactory.defaultRestTemplate();

		// Call the server with activation data
		System.out.println("Calling PowerAuth 2.0 Standard RESTful API at " + fullURIString + " ...");
		try {
			ResponseEntity<PowerAuthAPIResponse<ActivationCreateResponse>> response = template.exchange(request, new ParameterizedTypeReference<PowerAuthAPIResponse<ActivationCreateResponse>>() {
			});
			System.out.println("Done.");
			System.out.println();

			// Process the server response
			ActivationCreateResponse responseObject = response.getBody().getResponseObject();
			String activationId = responseObject.getActivationId();
			byte[] nonceServerBytes = BaseEncoding.base64().decode(responseObject.getActivationNonce());
			byte[] cServerPubKeyBytes = BaseEncoding.base64().decode(responseObject.getcServerPublicKey());
			byte[] cServerPubKeySignatureBytes = BaseEncoding.base64().decode(responseObject.getcServerPublicKeySignature());
			byte[] ephemeralKeyBytes = BaseEncoding.base64().decode(responseObject.getEphemeralPublicKey());
			PublicKey ephemeralPublicKey = keyConversion.convertBytesToPublicKey(ephemeralKeyBytes);

			// Verify that the server public key signature is valid
			boolean isDataSignatureValid = activation.verifyServerPublicKeySignature(cServerPubKeyBytes, cServerPubKeySignatureBytes, masterPublicKey);

			if (isDataSignatureValid) {

				// Decrypt the server public key
				PublicKey serverPublicKey = activation.decryptServerPublicKey(cServerPubKeyBytes, deviceKeyPair.getPrivate(), ephemeralPublicKey, activationOTP, activationIdShort, nonceServerBytes);

				// Compute master secret key
				SecretKey masterSecretKey = keyFactory.generateClientMasterSecretKey(deviceKeyPair.getPrivate(), serverPublicKey);

				// Derive PowerAuth keys from master secret key
				SecretKey signaturePossessionSecretKey = keyFactory.generateClientSignaturePossessionKey(masterSecretKey);
				SecretKey signatureKnoweldgeSecretKey = keyFactory.generateClientSignatureKnowledgeKey(masterSecretKey);
				SecretKey signatureBiometrySecretKey = keyFactory.generateClientSignatureBiometryKey(masterSecretKey);
				SecretKey transportMasterKey = keyFactory.generateServerTransportKey(masterSecretKey);
				// DO NOT EVER STORE ...
				SecretKey vaultUnlockMasterKey = keyFactory.generateServerEncryptedVaultKey(masterSecretKey);

				// Encrypt the original device private key using the vault unlock key
				byte[] encryptedDevicePrivateKey = vault.encryptDevicePrivateKey(deviceKeyPair.getPrivate(), vaultUnlockMasterKey);
				
				char[] password = null;
				if (passwordProvided == null) {
					Console console = System.console();
					password = console.readPassword("Select a password to encrypt the knowledge related key: ");
				} else {
					password = passwordProvided.toCharArray();
				}

				byte[] salt = keyGenerator.generateRandomBytes(16);
				byte[] cSignatureKnoweldgeSecretKey = EncryptedStorageUtil.storeSignatureKnowledgeKey(password, signatureKnoweldgeSecretKey, salt, keyGenerator);

				// Prepare the status object to be stored
				resultStatusObject.put("activationId", activationId);
				resultStatusObject.put("clientPublicKey", BaseEncoding.base64().encode(keyConversion.convertPublicKeyToBytes(deviceKeyPair.getPublic())));
				resultStatusObject.put("encryptedDevicePrivateKey", BaseEncoding.base64().encode(encryptedDevicePrivateKey));
				resultStatusObject.put("signaturePossessionKey", BaseEncoding.base64().encode(keyConversion.convertSharedSecretKeyToBytes(signaturePossessionSecretKey)));
				resultStatusObject.put("signatureKnowledgeKeyEncrypted", BaseEncoding.base64().encode(cSignatureKnoweldgeSecretKey));
				resultStatusObject.put("signatureKnowledgeKeySalt", BaseEncoding.base64().encode(salt));
				resultStatusObject.put("signatureBiometryKey", BaseEncoding.base64().encode(keyConversion.convertSharedSecretKeyToBytes(signatureBiometrySecretKey)));
				resultStatusObject.put("transportMasterKey", BaseEncoding.base64().encode(keyConversion.convertSharedSecretKeyToBytes(transportMasterKey)));
				resultStatusObject.put("counter", new Long(0));

				// Store the resulting status
				String formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
				try (FileWriter file = new FileWriter(statusFileName)) {
					file.write(formatted);
				}
				System.out.println("Activation ID: " + activationId);
				System.out.println("Activation data were stored in file: " + statusFileName);
				System.out.println("Activation data file contents: " + formatted);
				System.out.println();

				// Show the device fingerprint for the visual control data was received correctly on the server
				System.out.println("Check the device public key fingerprint: " + activation.computeDevicePublicKeyFingerprint(deviceKeyPair.getPublic()));
				System.out.println();
				System.out.println("### Done.");
				System.out.println();
				
				return resultStatusObject;

			} else {
				System.out.println("Activation data signature does not match. Either someone tried to spoof your connection, or your device master key is invalid.");
				System.out.println();
				System.out.println("### Failed.");
				System.out.println();
				System.exit(1);
			}
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

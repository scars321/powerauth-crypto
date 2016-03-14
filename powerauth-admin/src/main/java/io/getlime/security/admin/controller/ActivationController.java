package io.getlime.security.admin.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.google.common.io.BaseEncoding;

import io.getlime.powerauth.soap.ActivationStatus;
import io.getlime.powerauth.soap.CommitActivationResponse;
import io.getlime.powerauth.soap.GetActivationListForUserResponse;
import io.getlime.powerauth.soap.GetActivationStatusResponse;
import io.getlime.powerauth.soap.GetApplicationDetailResponse;
import io.getlime.powerauth.soap.GetApplicationListResponse;
import io.getlime.powerauth.soap.InitActivationResponse;
import io.getlime.powerauth.soap.SignatureAuditResponse;
import io.getlime.security.soap.client.PowerAuthServiceClient;
import io.getlime.security.util.QRUtil;

@Controller
public class ActivationController {

	@Autowired
	private PowerAuthServiceClient client;

	@RequestMapping(value = "/activation/list")
	public String activationList(
			@RequestParam(value = "userId", required = false) String userId, 
			@RequestParam(value = "showAll", required = false) Boolean showAll, 
			Map<String, Object> model) {
		if (userId != null) {
			List<GetActivationListForUserResponse.Activations> activationList = client.getActivationListForUser(userId);
			Collections.sort(activationList, new Comparator<GetActivationListForUserResponse.Activations>() {

				@Override
				public int compare(GetActivationListForUserResponse.Activations o1, GetActivationListForUserResponse.Activations o2) {
					return o2.getTimestampLastUsed().compare(o1.getTimestampLastUsed());
				}
				
			});
			
			model.put("activations", activationList);
			model.put("userId", userId);
			model.put("showAll", showAll);

			List<GetApplicationListResponse.Applications> applications = client.getApplicationList();
			model.put("applications", applications);
		}
		return "activations";
	}

	@RequestMapping(value = "/activation/detail/{id}")
	public String activationDetail(@PathVariable String id, @RequestParam(value = "userId", required = false) String userId, Map<String, Object> model) {
		GetActivationStatusResponse activation = client.getActivationStatus(id);
		model.put("activationId", activation.getActivationId());
		model.put("activationName", activation.getActivationName());
		model.put("status", activation.getActivationStatus());
		model.put("timestampCreated", activation.getTimestampCreated());
		model.put("timestampLastUsed", activation.getTimestampLastUsed());
		model.put("userId", activation.getUserId());

		GetApplicationDetailResponse application = client.getApplicationDetail(activation.getApplicationId());
		model.put("applicationId", application.getApplicationId());
		model.put("applicationName", application.getApplicationName());
		
		Date endingDate = new Date();
		Date startingDate = new Date(endingDate.getTime() - (30L * 24L * 60L * 60L * 1000L));
		List<SignatureAuditResponse.Items> auditItems = client.getSignatureAuditLog(userId, application.getApplicationId(), startingDate, endingDate);
		List<SignatureAuditResponse.Items> auditItemsFixed = new ArrayList<>();
		for (SignatureAuditResponse.Items item : auditItems) {
			if (item.getActivationId().equals(activation.getActivationId())) {
				item.setDataBase64(new String(BaseEncoding.base64().decode(item.getDataBase64())));
				auditItemsFixed.add(0, item);
			}
		}
		if (auditItemsFixed.size() > 5) {
			auditItemsFixed = auditItemsFixed.subList(0, 5);
		}
		model.put("signatures", auditItemsFixed);
		
		if (activation.getActivationStatus().equals(ActivationStatus.CREATED)) {
			String activationIdShort = activation.getActivationIdShort();
			String activationOtp = activation.getActivationOTP();
			String activationSignature = activation.getActivationSignature();
			model.put("activationIdShort", activationIdShort);
			model.put("activationOtp", activationOtp);
			model.put("activationSignature", activationSignature);
			model.put("activationQR", QRUtil.encode(activationIdShort + "-" + activationOtp + "#" + activationSignature, 400));
		}

		return "activationDetail";
	}

	@RequestMapping(value = "/activation/create")
	public String activationCreate(@RequestParam(value = "applicationId") Long applicationId, @RequestParam(value = "userId") String userId, Map<String, Object> model) {

		InitActivationResponse response = client.initActivation(userId, applicationId);

		model.put("activationIdShort", response.getActivationIdShort());
		model.put("activationId", response.getActivationId());
		model.put("activationOTP", response.getActivationOTP());
		model.put("activationSignature", response.getActivationSignature());

		return "redirect:/activation/detail/" + response.getActivationId();
	}
	
	@RequestMapping(value = "/activation/create/do.submit", method = RequestMethod.POST)
	public String activationCreateCommitAction(@RequestParam(value = "activationId") String activationId, Map<String, Object> model) {
		CommitActivationResponse commitActivation = client.commitActivation(activationId);
		return "redirect:/activation/detail/" + commitActivation.getActivationId();
	}

}

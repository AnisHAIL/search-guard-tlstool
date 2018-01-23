package com.floragunn.searchguard.tools.tlstool.tasks;

import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.floragunn.searchguard.support.WildcardMatcher;
import com.floragunn.searchguard.tools.tlstool.Config;
import com.floragunn.searchguard.tools.tlstool.Context;
import com.floragunn.searchguard.tools.tlstool.ResultConfig;
import com.floragunn.searchguard.tools.tlstool.ToolException;
import com.google.common.base.Strings;

public class CreateNodeCertificate extends CreateNodeCertificateBase {

	private static int generatedCertificateCount = 0;
	private static boolean passwordAutoGenerated = false;

	private Config.Node nodeConfig;
	private File certificateFile;
	private File httpCertificateFile;

	public CreateNodeCertificate(Context ctx, Config.Node nodeConfig) {
		super(ctx, nodeConfig);
		this.nodeConfig = nodeConfig;
	}

	@Override
	public void run() throws ToolException {
		privateKeyFile = new File(getNodeFileName(nodeConfig) + ".key");
		certificateFile = new File(getNodeFileName(nodeConfig) + ".pem");
		httpPrivateKeyFile = new File(getNodeFileName(nodeConfig) + "_http.key");
		httpCertificateFile = new File(getNodeFileName(nodeConfig) + "_http.pem");

		configSnippetFile = new File(getNodeFileName(nodeConfig) + "_elasticsearch_config_snippet.yml");

		if (!checkFileOverwrite("certificate", nodeConfig.getDn(), privateKeyFile, certificateFile, httpPrivateKeyFile,
				httpCertificateFile)) {
			return;
		}

		createTransportCertificate();

		if (ctx.getConfig().getDefaults().isHttpEnabled()) {
			createRestCertificate();
		}

		addOutputFile(configSnippetFile, createConfigSnippet());
	}

	private void createTransportCertificate() throws ToolException {
		try {
			KeyPair nodeKeyPair = generateKeyPair(nodeConfig.getKeysize());

			SubjectPublicKeyInfo subPubKeyInfo = ctx.getSigningCertificate().getSubjectPublicKeyInfo();
			X500Name subjectName = createDn(nodeConfig.getDn(), "node");
			Date validityStartDate = new Date(System.currentTimeMillis());
			Date validityEndDate = getEndDate(validityStartDate, nodeConfig.getValidityDays());

			X509v3CertificateBuilder builder = new X509v3CertificateBuilder(ctx.getSigningCertificate().getSubject(),
					ctx.nextId(), validityStartDate, validityEndDate, subjectName, subPubKeyInfo);

			JcaX509ExtensionUtils extUtils = getExtUtils();

			builder.addExtension(Extension.authorityKeyIdentifier, false,
					extUtils.createAuthorityKeyIdentifier(ctx.getSigningCertificate()))
					.addExtension(Extension.subjectKeyIdentifier, false,
							extUtils.createSubjectKeyIdentifier(nodeKeyPair.getPublic()))
					.addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
					.addExtension(Extension.keyUsage, true,
							new KeyUsage(
									KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment))
					.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(
							new KeyPurposeId[] { KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth }));

			builder.addExtension(Extension.subjectAlternativeName, false,
					new DERSequence(createSubjectAlternativeNameList(true)));

			X509CertificateHolder nodeCertificate = builder.build(new JcaContentSignerBuilder("SHA1withRSA")
					.setProvider(ctx.getSecurityProvider()).build(nodeKeyPair.getPrivate()));

			String privateKeyPassword = getPassword(nodeConfig.getPkPassword());

			addEncryptedOutputFile(privateKeyFile, privateKeyPassword, nodeKeyPair.getPrivate());
			addOutputFile(certificateFile, ctx.getSigningCertificate(), nodeCertificate);

			nodeResultConfig.setTransportPemCertFilePath(certificateFile.getPath());
			nodeResultConfig.setTransportPemKeyFilePath(privateKeyFile.getPath());
			nodeResultConfig.setTransportPemKeyPassword(privateKeyPassword);
			nodeResultConfig.setTransportPemTrustedCasFilePath(ctx.getRootCaFile().toString());

			generatedCertificateCount++;

			if (isPasswordAutoGenerationEnabled(nodeConfig.getPkPassword())) {
				passwordAutoGenerated = true;
			}
		} catch (CertIOException | OperatorCreationException e) {
			throw new ToolException("Error while composing certificate for " + nodeConfig, e);
		}
	}

	private void createRestCertificate() throws ToolException {

		try {
			if (httpPrivateKeyFile.exists() || httpCertificateFile.exists()) {
				// TODO logging
				return;
			}

			KeyPair nodeKeyPair = generateKeyPair(nodeConfig.getKeysize());

			SubjectPublicKeyInfo subPubKeyInfo = ctx.getSigningCertificate().getSubjectPublicKeyInfo();
			X500Name subjectName = createDn(nodeConfig.getDn(), "node");
			Date validityStartDate = new Date(System.currentTimeMillis());
			Date validityEndDate = getEndDate(validityStartDate, nodeConfig.getValidityDays());

			X509v3CertificateBuilder builder = new X509v3CertificateBuilder(ctx.getSigningCertificate().getSubject(),
					ctx.nextId(), validityStartDate, validityEndDate, subjectName, subPubKeyInfo);

			JcaX509ExtensionUtils extUtils = getExtUtils();

			builder.addExtension(Extension.authorityKeyIdentifier, false,
					extUtils.createAuthorityKeyIdentifier(ctx.getSigningCertificate()))
					.addExtension(Extension.subjectKeyIdentifier, false,
							extUtils.createSubjectKeyIdentifier(nodeKeyPair.getPublic()))
					.addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
					.addExtension(Extension.keyUsage, true,
							new KeyUsage(
									KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment))
					.addExtension(Extension.extendedKeyUsage, true,
							new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.id_kp_serverAuth }));

			builder.addExtension(Extension.subjectAlternativeName, false,
					new DERSequence(createSubjectAlternativeNameList(false)));

			X509CertificateHolder nodeCertificate = builder.build(new JcaContentSignerBuilder("SHA1withRSA")
					.setProvider(ctx.getSecurityProvider()).build(nodeKeyPair.getPrivate()));

			String privateKeyPassword = getPassword(nodeConfig.getPkPassword());

			addEncryptedOutputFile(httpPrivateKeyFile, privateKeyPassword, nodeKeyPair.getPrivate());
			addOutputFile(httpCertificateFile, ctx.getSigningCertificate(), nodeCertificate);

			nodeResultConfig.setHttpPemCertFilePath(certificateFile.getPath());
			nodeResultConfig.setHttpPemKeyFilePath(privateKeyFile.getPath());
			nodeResultConfig.setHttpPemKeyPassword(privateKeyPassword);
			nodeResultConfig.setHttpPemTrustedCasFilePath(ctx.getRootCaFile().toString());

			generatedCertificateCount++;

			if (isPasswordAutoGenerationEnabled(nodeConfig.getPkPassword())) {
				passwordAutoGenerated = true;
			}

		} catch (CertIOException | OperatorCreationException e) {
			throw new ToolException("Error while composing HTTP certificate for " + nodeConfig, e);
		}
	}

	public static int getGeneratedCertificateCount() {
		return generatedCertificateCount;
	}

	public static boolean isPasswordAutoGenerated() {
		return passwordAutoGenerated;
	}

}

/*
 * Copyright 2017-2018 floragunn GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.tools.tlstool.tasks;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import com.floragunn.searchguard.tools.tlstool.Config;
import com.floragunn.searchguard.tools.tlstool.Context;
import com.floragunn.searchguard.tools.tlstool.ToolException;

public class CreateNodeCsr extends CreateNodeCertificateBase {
	private static int generatedCsrCount = 0;
	private static boolean passwordAutoGenerated = false;

	private Config.Node nodeConfig;
	private File transportCsrFile;
	private File httpCsrFile;

	public CreateNodeCsr(Context ctx, Config.Node nodeConfig) {
		super(ctx, nodeConfig);
		this.nodeConfig = nodeConfig;
	}

	@Override
	public void run() throws ToolException {
		privateKeyFile = new File(ctx.getTargetDirectory(), getNodeFileName(nodeConfig) + ".key");
		transportCsrFile = new File(ctx.getTargetDirectory(), getNodeFileName(nodeConfig) + ".csr");
		httpPrivateKeyFile = new File(ctx.getTargetDirectory(), getNodeFileName(nodeConfig) + "_http.key");
		httpCsrFile = new File(ctx.getTargetDirectory(), getNodeFileName(nodeConfig) + "_http.csr");
		configSnippetFile = new File(ctx.getTargetDirectory(),
				getNodeFileName(nodeConfig) + "_elasticsearch_config_snippet.yml");

		if (!checkFileOverwrite("certificate", nodeConfig.getDn(), privateKeyFile, transportCsrFile, httpPrivateKeyFile,
				httpCsrFile)) {
			return;
		}

		createTransportCsr();

		if (ctx.getConfig().getDefaults().isHttpsEnabled()) {
			if (ctx.getConfig().getDefaults().isReuseTransportCertificatesForHttp()) {
				addTransportCertificateToConfigAsHttpCertificate();
			} else {
				createHttpCsr();
			}
		} else {
			nodeResultConfig.setHttpsEnabled(false);
		}

		addOutputFile(configSnippetFile, createConfigSnippetComment(), createConfigSnippet());

	}

	private String createConfigSnippetComment() {
		return "# This is a configuration snippet for the node " + getNodeFileName(nodeConfig) + "\n"
				+ "# Before you can proceed with configuration, you need to pass the generated signing request which can be found in the\n"
				+ "# file " + transportCsrFile.getName()
				+ (ctx.getConfig().getDefaults().isHttpsEnabled() ? " and " + httpCsrFile.getName() : "")
				+ " to your PKI in order to get the actual certificates.\n"
				+ "# If you do not have a PKI, you can use this tool with the options --create-ca and --create-cert to create a self signed CA\n"
				+ "# and sign the certificates with that CA.\n\n"
				+ "# The generated certificates need to be copied to the config directory of the node's ElasticSearch installation.\n"
				+ "# Furthermore, the private key files (with the suffix .key) generated by this tool need to be copied to that directory\n"
				+ "# as well.\n\n"
				+ "# This config snippet needs to be inserted into the file elasticsearch.yml which can be also found in the config dir.\n"
				+ "# If the config file already contains SearchGuard configuration, this needs to be replaced.\n"
				+ "# References to the PEM files for certificates need to be adjusted to match the names of the generated certificates.\n\n"
				+ "# Please refer to http://docs.search-guard.com/latest/configuring-tls for further configuration of your installation.\n\n\n";
	}

	private void createTransportCsr() throws ToolException {
		try {
			KeyPair nodeKeyPair = generateKeyPair(nodeConfig.getKeysize());

			PKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(
					new X500Principal(nodeConfig.getDn()), nodeKeyPair.getPublic());

			ExtensionsGenerator extensionsGenerator = new ExtensionsGenerator();

			extensionsGenerator.addExtension(Extension.keyUsage, true,
					new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment));

			extensionsGenerator.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(
					new KeyPurposeId[] { KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth }));

			extensionsGenerator.addExtension(Extension.subjectAlternativeName, false,
					new DERSequence(createSubjectAlternativeNameList(true)));

			builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensionsGenerator.generate());

			JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder(
					ctx.getConfig().getDefaults().getSignatureAlgorithm());
			ContentSigner signer = csBuilder.build(nodeKeyPair.getPrivate());
			org.bouncycastle.pkcs.PKCS10CertificationRequest csr = builder.build(signer);

			String privateKeyPassword = getPassword(nodeConfig.getPkPassword());

			addEncryptedOutputFile(privateKeyFile, privateKeyPassword, nodeKeyPair.getPrivate());
			addOutputFile(transportCsrFile, csr);

			nodeResultConfig.setTransportPemKeyFilePath(privateKeyFile.getPath());
			nodeResultConfig.setTransportPemKeyPassword(privateKeyPassword);
			nodeResultConfig.setTransportPemTrustedCasFilePath("<add path to trusted ca>");
			nodeResultConfig.setTransportPemCertFilePath(
					"<path to transport certificate for " + getNodeFileName(nodeConfig) + ">");

			generatedCsrCount++;

			if (isPasswordAutoGenerationEnabled(nodeConfig.getPkPassword())) {
				passwordAutoGenerated = true;
			}

		} catch (OperatorCreationException | IOException e) {
			throw new ToolException("Error while composing certificate signing reguest", e);
		}
	}

	private void createHttpCsr() throws ToolException {
		try {
			KeyPair nodeKeyPair = generateKeyPair(nodeConfig.getKeysize());

			PKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(
					createDn(nodeConfig.getDn(), "node"), nodeKeyPair.getPublic());

			ExtensionsGenerator extensionsGenerator = new ExtensionsGenerator();

			extensionsGenerator.addExtension(Extension.keyUsage, true,
					new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment));

			extensionsGenerator.addExtension(Extension.extendedKeyUsage, true,
					new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.id_kp_serverAuth }));

			extensionsGenerator.addExtension(Extension.subjectAlternativeName, false,
					new DERSequence(createSubjectAlternativeNameList(false)));

			builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensionsGenerator.generate());

			JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder(
					ctx.getConfig().getDefaults().getSignatureAlgorithm());
			ContentSigner signer = csBuilder.build(nodeKeyPair.getPrivate());
			org.bouncycastle.pkcs.PKCS10CertificationRequest csr = builder.build(signer);

			String privateKeyPassword = getPassword(nodeConfig.getPkPassword());

			addEncryptedOutputFile(httpPrivateKeyFile, privateKeyPassword, nodeKeyPair.getPrivate());
			addOutputFile(httpCsrFile, csr);

			nodeResultConfig.setHttpPemKeyFilePath(httpPrivateKeyFile.getPath());
			nodeResultConfig.setHttpPemKeyPassword(privateKeyPassword);
			nodeResultConfig.setHttpPemTrustedCasFilePath("<add path to trusted ca>");
			nodeResultConfig
					.setHttpPemCertFilePath("<path to HTTP certificate for " + getNodeFileName(nodeConfig) + ">");

			generatedCsrCount++;

			if (isPasswordAutoGenerationEnabled(nodeConfig.getPkPassword())) {
				passwordAutoGenerated = true;
			}

		} catch (OperatorCreationException | IOException e) {
			throw new ToolException("Error while composing HTTP certificate for " + nodeConfig, e);
		}
	}

	public static int getGeneratedCsrCount() {
		return generatedCsrCount;
	}

	public static boolean isPasswordAutoGenerated() {
		return passwordAutoGenerated;
	}

	private void addTransportCertificateToConfigAsHttpCertificate() {
		nodeResultConfig.setHttpPemCertFilePath(nodeResultConfig.getTransportPemCertFilePath());
		nodeResultConfig.setHttpPemKeyFilePath(nodeResultConfig.getTransportPemKeyFilePath());
		nodeResultConfig.setHttpPemKeyPassword(nodeResultConfig.getTransportPemKeyPassword());
		nodeResultConfig.setHttpPemTrustedCasFilePath(nodeResultConfig.getTransportPemCertFilePath());
	}
}

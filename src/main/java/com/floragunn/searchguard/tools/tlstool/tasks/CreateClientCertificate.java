package com.floragunn.searchguard.tools.tlstool.tasks;

import java.io.File;
import java.security.KeyPair;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

import com.floragunn.searchguard.tools.tlstool.Config;
import com.floragunn.searchguard.tools.tlstool.Context;
import com.floragunn.searchguard.tools.tlstool.ToolException;

public class CreateClientCertificate extends CreateClientCertificateBase {

	private static final Logger log = LogManager.getLogger(CreateClientCertificate.class);

	private static int generatedCertificateCount = 0;
	private static boolean passwordAutoGenerated = false;
	
	public CreateClientCertificate(Context ctx, Config.Client clientConfig) {
		super(ctx, clientConfig);
	}

	@Override
	public void run() throws ToolException {
		try {

			File privateKeyFile = new File(getClientFileName(clientConfig) + ".key");
			File certificateFile = new File(getClientFileName(clientConfig) + ".pem");
			File readmeFile = new File("client-certificates.readme");

			if (!checkFileOverwrite("certificate", clientConfig.getDn(), privateKeyFile, certificateFile)) {
				return;
			}
			
			KeyPair clientKeyPair = generateKeyPair(clientConfig.getKeysize());

			SubjectPublicKeyInfo subPubKeyInfo = ctx.getSigningCertificate().getSubjectPublicKeyInfo();

			X500Name subjectName = createDn(clientConfig.getDn(), "client");
			Date validityStartDate = new Date(System.currentTimeMillis());
			Date validityEndDate = getEndDate(validityStartDate, clientConfig.getValidityDays());

			X509v3CertificateBuilder builder = new X509v3CertificateBuilder(ctx.getSigningCertificate().getSubject(),
					ctx.nextId(), validityStartDate, validityEndDate, subjectName, subPubKeyInfo);

			JcaX509ExtensionUtils extUtils = getExtUtils();

			builder.addExtension(Extension.authorityKeyIdentifier, false,
					extUtils.createAuthorityKeyIdentifier(ctx.getSigningCertificate()))
					.addExtension(Extension.subjectKeyIdentifier, false,
							extUtils.createSubjectKeyIdentifier(clientKeyPair.getPublic()))
					.addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
					.addExtension(Extension.keyUsage, true,
							new KeyUsage(
									KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment))
					.addExtension(Extension.extendedKeyUsage, true,
							new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.id_kp_clientAuth }));

			X509CertificateHolder clientCertificate = builder.build(new JcaContentSignerBuilder("SHA1withRSA")
					.setProvider(ctx.getSecurityProvider()).build(clientKeyPair.getPrivate()));

			String privateKeyPassword = getPassword(clientConfig.getPkPassword());

			addOutputFile(certificateFile, ctx.getSigningCertificate(), clientCertificate);
			addEncryptedOutputFile(privateKeyFile, privateKeyPassword, clientKeyPair.getPrivate());
			addOutputFile(readmeFile, createReadme());
			
			if (isPasswordAutoGenerationEnabled(clientConfig.getPkPassword())) {
				appendOutputFile(readmeFile, createPasswordInfo(privateKeyFile, privateKeyPassword));
				passwordAutoGenerated = true;
			}
			
			generatedCertificateCount++;

		} catch (CertIOException | OperatorCreationException e) {
			throw new ToolException("Error while composing certificate", e);
		}
	}


	public static int getGeneratedCertificateCount() {
		return generatedCertificateCount;
	}

	public static boolean isPasswordAutoGenerated() {
		return passwordAutoGenerated;
	}

}

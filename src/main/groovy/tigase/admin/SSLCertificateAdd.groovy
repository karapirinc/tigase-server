/*
 * Tigase XMPP Server - The instant messaging server
 * Copyright (C) 2004 Tigase, Inc. (office@tigase.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
/*

Add an SSL certificate for a given domain.

AS:Description: Add SSL Certificate
AS:CommandId: ssl-certificate-add
AS:Component: vhost-man
 */

package tigase.admin

import groovy.transform.CompileStatic
import tigase.cert.CertificateEntry
import tigase.cert.CertificateUtil
import tigase.db.comp.ComponentRepository
import tigase.io.CertificateContainerIfc
import tigase.io.SSLContextContainerIfc
import tigase.kernel.core.Kernel
import tigase.server.Command
import tigase.server.Iq
import tigase.server.Packet
import tigase.vhosts.VHostItem
import tigase.xmpp.jid.BareJID

import java.security.cert.X509Certificate
import java.util.function.Function
import java.util.logging.Level
import java.util.logging.Logger

Kernel kernel = (Kernel) kernel;
def repo = (ComponentRepository) comp_repo
def p = (Packet) packet
def admins = (Set) adminsSet
def log = Logger.getLogger("tigase.admin");

@CompileStatic
Packet process(Kernel kernel, Logger log, ComponentRepository<VHostItem> repo, Iq packet, Set admins, Function<String,Boolean> isAllowedForDomain) {
	def MARKER = "command-marker"


	try {

		def VHOST = "VHost"
		def CERTIFICATE = "Certificate in PEM format"
		def SAVE_TO_DISK = "Save permanently (to disk or repository)"
		def USE_AS_DEFAULT = "Use as default"

		def stanzaFromBare = packet.getStanzaFrom().getBareJID()
		def isServiceAdmin = admins.contains(stanzaFromBare)

		def itemKey = Command.getFieldValue(packet, VHOST)
		def marker = Command.getFieldValue(packet, MARKER)
		def pemCertVals = Command.getFieldValues(packet, CERTIFICATE)
		def saveToDisk = Command.getCheckBoxFieldValue(packet, SAVE_TO_DISK)
		def useAsDefault = Command.getCheckBoxFieldValue(packet, USE_AS_DEFAULT)

// The first step - provide a list of all vhosts for the user
		if (itemKey == null) {
			Collection<VHostItem> items = repo.allItems()
			List<String> itemsStr = items.findAll { isAllowedForDomain.apply(it.getKey()) }.collect { it.getKey() };
			if (itemsStr.size() > 0) {
				String[] itemsStrArray = itemsStr.toArray(new String[itemsStr.size()]);
				def result = packet.commandResult(Command.DataType.form)
				Command.addFieldValue(result, VHOST, itemsStr[0], "List of VHosts",
									  itemsStrArray, itemsStrArray);
				return result
			} else {
				def result = packet.commandResult(Command.DataType.result)
				Command.addTextField(result, "Note", "You have no VHosts to manage");
				return result
			}
		}

// The second step - provide a form to fill be by the user for selected vhost
		if (marker == null) {
			VHostItem item = repo.getItem(itemKey)
			if (item == null) {
				def result = packet.commandResult(Command.DataType.result)
				Command.addTextField(result, "Error", "No such VHost, adding SSL Certificate impossible.");
				return result;
			} else {
				if (isAllowedForDomain.apply(itemKey)) {
					def result = packet.commandResult(Command.DataType.form)
					Command.addFieldValue(result, VHOST, itemKey, "text-single")
					Command.addFieldMultiValue(result, CERTIFICATE, [ "" ])
					Command.addCheckBoxField(result, SAVE_TO_DISK, true)
					Command.addCheckBoxField(result, USE_AS_DEFAULT, false)
					Command.addHiddenField(result, MARKER, MARKER)
					return result
				} else {
					def result = packet.commandResult(Command.DataType.result)
					Command.addTextField(result, "Error", "You do not have enough permissions to manage this VHost.")
					return result
				}
			}
		}

// The last step - process the form submitted by the user
		def result = packet.commandResult(Command.DataType.result)
		VHostItem item = repo.getItem(itemKey)

		if (item == null) {
			Command.addTextField(result, "Error", "No such VHost, loading SSL certificate impossible.")
		} else {
			if (isAllowedForDomain.apply(itemKey)) {
				def pemCert = pemCertVals.join('\n')
				// Basic certificate checks
				// For XMPP service nonAdmins (domain owners) the alias must match CN name in the certificate
				CertificateEntry certEntry = CertificateUtil.parseCertificate(new CharArrayReader(pemCert.toCharArray()))
				if (certEntry.getPrivateKey() == null) {
					Command.addTextField(result, "Error",
										 "Missing private key or private key encoded in uknown format.")
					Command.addTextField(result, "Note", "Private key cannot be encoded with a password.")
				} else {
					def certCName = CertificateUtil.getCertCName((X509Certificate) certEntry.getCertChain()[0])
					def subjectAltName = CertificateUtil.getCertAltCName((X509Certificate) certEntry.getCertChain()[0])
					if (hasPermissionToUpdate(item, isServiceAdmin, stanzaFromBare, log) &&
							isCertificateValidForVhost(itemKey, certCName, subjectAltName, log)) {
						def params = new HashMap()
						params.put(SSLContextContainerIfc.PEM_CERTIFICATE_KEY, pemCert)
						params.put(SSLContextContainerIfc.CERT_ALIAS_KEY, itemKey)
						params.put(SSLContextContainerIfc.CERT_SAVE_TO_DISK_KEY, saveToDisk.toString())
						params.put(SSLContextContainerIfc.DEFAULT_DOMAIN_CERT_KEY, useAsDefault.toString())
						CertificateContainerIfc certContainer = kernel.getInstance(CertificateContainerIfc.class);
						certContainer.addCertificates(params);
						Command.addTextField(result, "Note",
											 "SSL Certificate for domain: " + itemKey + " loaded successfully")
					} else {
						Command.addTextField(result, "Error",
											 "Neither certificate CName nor any of SubjectAlternativeNames match the domain name!")
					}
				}
			} else {
				Command.addTextField(result, "Error", "You are not the VHost owner or you have no " +
						"enough permission to change the VHost.")
			}
		}

		return result;
	} catch (Exception ex) {
		def result = packet.commandResult(Command.DataType.result);
		def errorMessage = ex.getMessage()
		if (ex.getCause()!=null) {
			errorMessage += "\n" + ex.getCause().getMessage();
		}
		Command.addTextField(result, "Error", errorMessage);
		log.log(Level.FINE, "Error while processing request", ex)
		return result;
	}
}

private static boolean hasPermissionToUpdate(VHostItem item, boolean isServiceAdmin, BareJID userJid, Logger log) {
	if (log.isLoggable(Level.FINEST)) {
		log.log(Level.FINEST, "hasPermissionToUpdate :: userJid: ${userJid}, isServiceAdmin: ${isServiceAdmin}, isOwner: ${item.isOwner(userJid.toString())}, isAdmin: ${item.isAdmin(userJid.toString())}")
	}
	isServiceAdmin || item.isOwner(userJid.toString()) || item.isAdmin(userJid.toString())
}

private static boolean isCertificateValidForVhost(String itemKey, String certCName, List<String> subjectAltName,
												  Logger log) {
	def wildcardItemKey = "*." + itemKey
	def result = certCName == itemKey || certCName == wildcardItemKey || subjectAltName.contains(itemKey) ||
			subjectAltName.contains(wildcardItemKey)
	if (log.isLoggable(Level.FINEST)) {
		Logger.getLogger("tigase.admin").
				log(Level.FINEST,
					"isCertificateValidForVhost:: itemKey: ${itemKey}, wildcardItemKey: ${wildcardItemKey}, certCName: ${certCName}, subjectAltName: ${subjectAltName}, result: ${result}")
	}
	return result
}

return process(kernel, log, repo, p, admins, (Function<String,Boolean>) isAllowedForDomain);
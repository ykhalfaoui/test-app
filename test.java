package com.yourcompany.integration.blueprism;

import com.yourcompany.integration.blueprism.config.BluePrismSoapConfig;
import com.yourcompany.integration.blueprism.dto.*;
import com.yourcompany.integration.blueprism.exception.BluePrismSoapException;
import com.yourcompany.integration.blueprism.exception.BluePrismSoapFaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.soap.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Client SOAP RPC/encoded pour BluePrism avec authentification BASIC
 * 
 * @author Yass
 */
@Service
@Slf4j
public class BluePrismSoapClient {
    
    private static final String NAMESPACE = "urn:blueprism:webservice:wsremediationservices";
    private static final String ENCODING_STYLE = SOAPConstants.URI_NS_SOAP_ENCODING;
    private static final String XSD_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    
    private final BluePrismSoapConfig config;
    private final MessageFactory messageFactory;
    
    public BluePrismSoapClient(
            BluePrismSoapConfig config,
            MessageFactory messageFactory) {
        this.config = config;
        this.messageFactory = messageFactory;
    }
    
    /**
     * Opération BlockClient
     */
    public BlockClientResponse blockClient(BlockClientRequest request) {
        Map<String, Object> params = new HashMap<>();
        params.put("bpInstance", request.getBpInstance());
        params.put("ClientID", request.getClientId());
        params.put("Letter", request.getLetter());
        params.put("Blocking", request.isBlocking());
        params.put("DossierNumber", request.getDossierNumber());
        params.put("Agent", request.getAgent());
        
        Map<String, Object> response = callSoapOperation("BlockClient", params);
        
        return BlockClientResponse.builder()
            .success(parseBoolean(response.get("Success")))
            .message(parseString(response.get("Message")))
            .build();
    }
    
    /**
     * Opération Ping
     */
    public PingResponse ping(String bpInstance, String check) {
        Map<String, Object> params = new HashMap<>();
        params.put("bpInstance", bpInstance);
        params.put("Check", check);
        
        Map<String, Object> response = callSoapOperation("Ping", params);
        
        return PingResponse.builder()
            .check(parseString(response.get("Check")))
            .success(parseBoolean(response.get("Success")))
            .build();
    }
    
    /**
     * Appel SOAP avec authentification BASIC
     */
    private Map<String, Object> callSoapOperation(String operationName, Map<String, Object> parameters) {
        
        try {
            // 1. Construire le message SOAP
            SOAPMessage request = buildRpcEncodedMessage(operationName, parameters);
            
            // 2. Logger la requête
            if (config.isEnableDetailedLogging()) {
                log.debug("SOAP REQUEST:\n{}", soapMessageToString(request));
            }
            
            // 3. Appeler via HttpURLConnection avec authentification
            SOAPMessage response = callWithBasicAuth(request);
            
            // 4. Logger la réponse
            if (config.isEnableDetailedLogging()) {
                log.debug("SOAP RESPONSE:\n{}", soapMessageToString(response));
            }
            
            // 5. Parser la réponse
            return parseRpcEncodedResponse(response, operationName);
            
        } catch (Exception e) {
            log.error("SOAP call failed for operation: {}", operationName, e);
            throw new BluePrismSoapException("SOAP operation failed: " + operationName, e);
        }
    }
    
    /**
     * ⭐ Appeler le service SOAP avec authentification BASIC
     */
    private SOAPMessage callWithBasicAuth(SOAPMessage request) throws Exception {
        
        // 1. Créer la connexion HTTP
        URL url = new URL(config.getEndpoint());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        // 2. Configurer la connexion
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        connection.setRequestProperty("SOAPAction", "\"\"");
        
        // ⭐ 3. AJOUTER L'AUTHENTIFICATION BASIC
        if (config.getUsername() != null && config.getPassword() != null) {
            String auth = config.getUsername() + ":" + config.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(
                auth.getBytes(StandardCharsets.UTF_8)
            );
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
            
            log.debug("Added Basic Authentication for user: {}", config.getUsername());
        }
        
        // 4. Configurer les timeouts
        connection.setConnectTimeout(config.getConnectionTimeout());
        connection.setReadTimeout(config.getSocketTimeout());
        
        // 5. Envoyer la requête SOAP
        try (OutputStream os = connection.getOutputStream()) {
            request.writeTo(os);
        }
        
        // 6. Vérifier le code de réponse HTTP
        int responseCode = connection.getResponseCode();
        log.debug("HTTP Response Code: {}", responseCode);
        
        if (responseCode == 401) {
            throw new BluePrismSoapException(
                "Authentication failed (401 Unauthorized). " +
                "Please check username and password in application.yml"
            );
        }
        
        if (responseCode >= 400) {
            throw new BluePrismSoapException(
                "HTTP Error: " + responseCode + " - " + connection.getResponseMessage()
            );
        }
        
        // 7. Lire la réponse SOAP
        return messageFactory.createMessage(null, connection.getInputStream());
    }
    
    /**
     * Construction du message SOAP RPC/encoded
     */
    private SOAPMessage buildRpcEncodedMessage(String operationName, Map<String, Object> parameters) 
            throws SOAPException {
        
        SOAPMessage message = messageFactory.createMessage();
        SOAPPart soapPart = message.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();
        
        // Namespaces
        envelope.addNamespaceDeclaration("xsi", XSI_NS);
        envelope.addNamespaceDeclaration("xsd", XSD_NS);
        envelope.addNamespaceDeclaration("soapenc", ENCODING_STYLE);
        envelope.addNamespaceDeclaration("tns", NAMESPACE);
        
        SOAPBody body = envelope.getBody();
        SOAPElement operation = body.addChildElement(operationName, "tns");
        operation.setEncodingStyle(ENCODING_STYLE);
        
        // Ajouter les paramètres
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            addRpcEncodedParameter(envelope, operation, entry.getKey(), entry.getValue());
        }
        
        message.saveChanges();
        return message;
    }
    
    /**
     * Ajouter un paramètre RPC/encoded
     */
    private void addRpcEncodedParameter(
            SOAPEnvelope envelope,
            SOAPElement parent,
            String name,
            Object value) throws SOAPException {
        
        SOAPElement param = parent.addChildElement(name);
        
        if (value == null) {
            param.addAttribute(envelope.createName("nil", "xsi", XSI_NS), "true");
            return;
        }
        
        String xsdType = getXsdType(value);
        param.addAttribute(
            envelope.createName("type", "xsi", XSI_NS),
            "xsd:" + xsdType
        );
        param.addTextNode(String.valueOf(value));
    }
    
    /**
     * Déterminer le type XSD
     */
    private String getXsdType(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof BigDecimal) return "decimal";
        if (value instanceof LocalDate) return "date";
        return "string";
    }
    
    /**
     * Parser la réponse SOAP
     */
    private Map<String, Object> parseRpcEncodedResponse(SOAPMessage response, String operationName) 
            throws SOAPException {
        
        SOAPBody body = response.getSOAPBody();
        
        if (body.hasFault()) {
            SOAPFault fault = body.getFault();
            throw new BluePrismSoapFaultException(
                fault.getFaultCode(),
                fault.getFaultString()
            );
        }
        
        Map<String, Object> result = new HashMap<>();
        NodeList responseNodes = body.getElementsByTagName(operationName + "Response");
        
        if (responseNodes.getLength() > 0) {
            Node responseNode = responseNodes.item(0);
            NodeList children = responseNode.getChildNodes();
            
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    result.put(child.getLocalName(), child.getTextContent());
                }
            }
        }
        
        return result;
    }
    
    private String parseString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
    
    private Boolean parseBoolean(Object value) {
        if (value == null) return null;
        String str = String.valueOf(value).toLowerCase();
        return "true".equals(str) || "1".equals(str);
    }
    
    private String soapMessageToString(SOAPMessage message) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[Failed to convert SOAP message]";
        }
    }
}

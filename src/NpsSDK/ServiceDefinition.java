package NpsSDK;

import java.io.DataInputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import NpsSDK.ILogger.LogLevel;

public class ServiceDefinition {

	// Attributes

	private WsdlHandlerConfiguration _wsdlHandlerConfiguration;
	private String serviceName;
	private String inputType;
	private String outputType;
	private String inputParameterName;
	private String outputParameterName;

	private Node input;
	private Node output;

	public ServiceDefinition(WsdlHandlerConfiguration wsdlHandlerConfiguration) {
		_wsdlHandlerConfiguration = wsdlHandlerConfiguration;
	}

	public String getServiceName() {
		return serviceName;
	}

	public void setServiceName(String serviceName) {
		this.serviceName = serviceName;
	}

	String getInputType() {
		return inputType;
	}

	void setInputType(String inputType) {
		this.inputType = inputType;
	}

	String getOutputType() {
		return outputType;
	}

	void setOutputType(String outputType) {
		this.outputType = outputType;
	}

	public String getInputParameterName() {
		return inputParameterName;
	}

	public void setInputParameterName(String inputParameterName) {
		this.inputParameterName = inputParameterName;
	}

	public String getOutputParameterName() {
		return outputParameterName;
	}

	public void setOutputParameterName(String outputParameterName) {
		this.outputParameterName = outputParameterName;
	}

	public Node getInput() {
		return input;
	}

	public void setInput(Node input) {
		this.input = input;
	}

	public Node getOutput() {
		return output;
	}

	public void setOutput(Node output) {
		this.output = output;
	}

	private Boolean validate(BaseElement data, List<String> errorMessage) throws WsdlHandlerException {
		return validate(data, input, "", errorMessage);
	}

	private Boolean validate(BaseElement data, Node parentNode, String path, List<String> errors)
			throws WsdlHandlerException {
		List<BaseElement> dataChildren = new ArrayList<BaseElement>(data.getChildren());
		Collections.sort(dataChildren, new Comparator<BaseElement>() {
			@Override
			public int compare(final BaseElement element1, final BaseElement element2) {
				return element1.getName().compareTo(element2.getName());
			}
		});

		List<Node> nodeChildren = new ArrayList<Node>(parentNode.getChildren());
		Collections.sort(nodeChildren, new Comparator<Node>() {
			@Override
			public int compare(final Node node1, final Node node2) {
				return node1.getNodeName().compareTo(node2.getNodeName());
			}
		});
		int dataCounter = 0, nodeCounter = 0;
		while (nodeCounter < nodeChildren.size() || dataCounter < dataChildren.size()) {

			if (dataCounter > 0 && dataCounter < dataChildren.size()
					&& dataChildren.get(dataCounter - 1).getName().equals(dataChildren.get(dataCounter).getName())) {
				errors.add("Duplicate field: " + dataChildren.get(dataCounter).getName());
				dataCounter++;
				continue;
			}

			int compare = nodeCounter == nodeChildren.size() ? 1
					: (dataCounter == dataChildren.size() ? -1
							: nodeChildren.get(nodeCounter).getNodeName()
									.compareTo(dataChildren.get(dataCounter).getName()));

			if (compare < 0) {
				if (nodeChildren.get(nodeCounter).isMandatory()) {
					errors.add("Missing field: " + path + nodeChildren.get(nodeCounter).getNodeName());
				}

				if (nodeChildren.get(nodeCounter).getNodeName().equals("psp_SecureHash") && data instanceof RootElement
						&& !isPspClientSessionInDataChildren(data)) {
					data.getChildren().add(new SimpleElement("psp_SecureHash",
							((RootElement) data).secureHash(_wsdlHandlerConfiguration.getSecretKey())));
				}
				if (nodeChildren.get(nodeCounter).getNodeName().equals("SdkInfo")) {
					data.getChildren().add(new SimpleElement("SdkInfo", NpsSdk.sdkVersion));
				}
				nodeCounter++;
				continue;
			}
			if (compare > 0) {
				// errors.add("No matching field: " +
				// dataChildren.get(dataCounter).getName());
				data.getChildren().remove(dataChildren.get(dataCounter));
				dataCounter++;
				continue;
			}

			if (!nodeChildren.get(nodeCounter).isSimpleType() != dataChildren
					.get(dataCounter) instanceof ComplexElement) {

				if (!nodeChildren.get(nodeCounter).isArray()
						|| (dataChildren.get(dataCounter) instanceof ComplexElementArray == false
								&& dataChildren.get(dataCounter) instanceof SimpleElementArray == false)) {
					errors.add("Wrong field type: " + dataChildren.get(dataCounter).getName());
				} else {
					if (nodeChildren.get(nodeCounter).isArray()) {
						if(dataChildren.get(dataCounter) instanceof ComplexElementArray){
							((ComplexElementArray) dataChildren.get(dataCounter)).setChildType(nodeChildren.get(nodeCounter).getArrayBaseType().getNodeType());
						}
						for (int i = 0; i < dataChildren.get(dataCounter).getChildren().size(); i++) {
							BaseElement arrayElement = dataChildren.get(dataCounter).getChildren().get(i);
							if (arrayElement instanceof ComplexElementArrayItem == nodeChildren.get(nodeCounter)
									.getArrayBaseType().isSimpleType()) {
								errors.add("Wrong type in array: " + path + dataChildren.get(dataCounter).getName());
							} else {
								List<String> innerErrors = new ArrayList<String>();
								if (!validate(arrayElement, nodeChildren.get(nodeCounter).getArrayBaseType(),
										String.format("{0}{1}[{2}] --> ", path,
												nodeChildren.get(nodeCounter).getNodeName(), i),
										innerErrors)) {
									errors.addAll(innerErrors);
								}
							}
						}
					}
				}
			} else if (!nodeChildren.get(nodeCounter).isSimpleType()) {
				List<String> innerErrors = new ArrayList<String>();
				validate(dataChildren.get(dataCounter), nodeChildren.get(nodeCounter),
						String.format("{0}{1} --> ", path, nodeChildren.get(nodeCounter).getNodeName()), innerErrors);
				errors.addAll(innerErrors);
			} else {
				if (!isStringNullOrWhiteSpace(parentNode.getNodeName())) {
					SimpleElement simpleElement = (dataChildren.get(dataCounter) instanceof SimpleElement
							? (SimpleElement) dataChildren.get(dataCounter) : null);
					if (simpleElement != null) {
						String key = String.format("%1$s.%2$s", parentNode.getNodeName(), simpleElement.getName());

						if (NpsSdk.fieldsMaxLength.containsKey(key)) {
							simpleElement.trim(NpsSdk.fieldsMaxLength.get(key));
						}
					}
				}
			}
			dataCounter++;
			nodeCounter++;
		}

		return errors.size() == 0;
	}

	private Boolean isPspClientSessionInDataChildren(BaseElement data) {
		Boolean pspClientSessionInChildren = false;
		for (BaseElement baseElement : data.getChildren()) {
			if (baseElement.getName().equals("psp_ClientSession")) {
				pspClientSessionInChildren = true;
			}
		}
		return pspClientSessionInChildren;
	}

	private Boolean isStringNullOrWhiteSpace(String value) {
		if (value == null) {
			return true;
		}

		for (int i = 0; i < value.length(); i++) {
			if (!Character.isWhitespace(value.charAt(i))) {
				return false;
			}
		}

		return true;
	}

	RootElement call(RootElement data) throws WsdlHandlerException {
		List<String> errors = new ArrayList<String>();
		if (!validate(data, errors)) {
			throw new WsdlHandlerException(String.join("\n", errors));
		}

		String url = _wsdlHandlerConfiguration.getServiceUrl();

		String shortUrl = "";
		if (url.endsWith(".php")) {
			shortUrl = url.substring(0, url.length() - 4);
		}

		String xml = String.format(
				"<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"><q1:%2$s xmlns:q1=\"%1$s\"><%3$s href=\"#id1\"/></q1:%2$s><q2:%4$s id=\"id1\" xsi:type=\"q2:%4$s\" xmlns:q2=\"%1$s\">%5$s</q2:%4$s></s:Body></s:Envelope>",
				shortUrl, serviceName, inputParameterName, inputType, data.serialize());

		_wsdlHandlerConfiguration.getLogger().logRequest(LogLevel.INFO, xml);

		String response = callSOAPServer(url, serviceName, xml, inputType, inputParameterName, outputParameterName,
				data);

		_wsdlHandlerConfiguration.getLogger().logResponse(LogLevel.INFO, response);

		RootElement rootElement = new RootElement();
		Document xmlDoc = this.convertStringToDocument(response);

		NodeList respuesta = xmlDoc.getElementsByTagName(outputParameterName);
		if (respuesta.getLength() != 1) {
			return null;
		}

		NodeList nodeList = respuesta.item(0).getChildNodes();
		for (int i = 0; i < nodeList.getLength(); i++) {
			rootElement.add(nodeList.item(i).getNodeName(), this.deserialize(nodeList.item(i), false));
		}

		return rootElement;
	}

	private String callSOAPServer(String url, String service, String xml, String requestType, String inputParameterName,
			String outputParameterName, BaseElement data) throws WsdlHandlerException {

		byte[] result = null;
		String action = url + "/" + service;

		HttpParams httpParameters = new BasicHttpParams();

		int connectionTimeOut = _wsdlHandlerConfiguration.getOpenTimeOut() * 1000;
		HttpConnectionParams.setConnectionTimeout(httpParameters, connectionTimeOut);
		int socketTimeOut = _wsdlHandlerConfiguration.getReadTimeOut() * 1000;
		HttpConnectionParams.setSoTimeout(httpParameters, socketTimeOut);
		DefaultHttpClient httpclient = new DefaultHttpClient(httpParameters);

		HttpPost httppost = new HttpPost(url);

		if (_wsdlHandlerConfiguration.getProxy() != null) {
			httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, _wsdlHandlerConfiguration.getProxy());
		}

		httppost.addHeader("soapaction", action);
		httppost.addHeader("Pragma", "no-cache");
		httppost.addHeader("Connection", "Keep-Alive");
		httppost.addHeader("Content-Type", "text/xml; charset=utf-8");

		try {
			HttpEntity entity = new StringEntity(xml, StandardCharsets.UTF_8);
			httppost.setEntity(entity);
			HttpResponse response = httpclient.execute(httppost);
			HttpEntity r_entity = response.getEntity();
			if (r_entity != null) {
				result = new byte[(int) r_entity.getContentLength()];
				if (r_entity.isStreaming()) {
					DataInputStream is = new DataInputStream(r_entity.getContent());
					is.readFully(result);
				}
			}

		} catch (ConnectTimeoutException ex) {
			throw new WsdlHandlerException("Connection timeout", ex);
		} catch (SocketTimeoutException ex) {
			throw new WsdlHandlerException("Read timeout", ex);
		} catch (Exception ex) {
			throw new WsdlHandlerException(ex);
		}

		httpclient.getConnectionManager().shutdown();
		String stringResult = "";

		try {
			stringResult = new String(result, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new WsdlHandlerException(e);
		}

		return stringResult;
	}

	private BaseElement deserialize(org.w3c.dom.Node xmlNode, Boolean arrayItem) throws WsdlHandlerException {
		if (xmlNode.getChildNodes().getLength() == 1
				&& xmlNode.getFirstChild().getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
			return new SimpleElement(xmlNode.getNodeName(), xmlNode.getFirstChild().getNodeValue());
		}
		if (xmlNode.getChildNodes().getLength() > 0 && esArray(xmlNode)) {
			Boolean isSimpleArray = true;
			List<BaseElement> items = new ArrayList<BaseElement>();
			NodeList childNodes = xmlNode.getChildNodes();
			for (int i = 0; i < childNodes.getLength(); i++) {
				BaseElement item = deserialize(childNodes.item(i), true);
				isSimpleArray = isSimpleArray && item instanceof SimpleElement;
				items.add(item);
			}
			if (isSimpleArray) {
				SimpleElementArray simpleElementArray = new SimpleElementArray();
				simpleElementArray.setName(xmlNode.getNodeName());
				for (BaseElement item : items) {
					simpleElementArray.add(((SimpleElement) item).getConcatenatedValues());
				}
				return simpleElementArray;
			}
			ComplexElementArray complexElementArray = new ComplexElementArray();
			complexElementArray.setName(xmlNode.getNodeName());
			for (BaseElement item : items) {
				complexElementArray.add((ComplexElementArrayItem) item);
			}
			return complexElementArray;
		}
		ComplexElement complexElement = arrayItem ? new ComplexElementArrayItem() : new ComplexElement();
		complexElement.setName(xmlNode.getNodeName());
		NodeList childNodes = xmlNode.getChildNodes();
		for (int i = 0; i < childNodes.getLength(); i++) {
			complexElement.add(childNodes.item(i).getNodeName(), deserialize(childNodes.item(i), false));
		}
		return complexElement;
	}

	private Boolean esArray(org.w3c.dom.Node xmlNode) {
		NodeList childNodes = xmlNode.getChildNodes();
		for (int i = 0; i < childNodes.getLength(); i++) {
			if (!childNodes.item(i).getNodeName().equals("item")) {
				return false;
			}
		}
		return true;
	}

	private Document convertStringToDocument(String xmlStr) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder;
		try {
			builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new InputSource(new StringReader(xmlStr)));
			return doc;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}
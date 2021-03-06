package ru.itx.proxyapp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class ProxyApp {

	@XmlRootElement(name="proc") private static class Process {
		@XmlAttribute public String exec;
		@XmlAttribute public Integer rss;
	}
	
	private static class Command {
		@XmlElement(name="Name") public String name;
		@XmlElement(name="Size") public Integer size;
		public Command(Process process) {
			this.name = process.exec;
			this.size = process.rss;		
		}
	}
	
	private static class Answer {
		@XmlElement(name="Command") public List<Command> commands = new ArrayList<Command>();
	}
	
	@XmlRootElement(name="Root") public static class Root {
		@XmlElement(name="Answer") public List<Answer> answers = new ArrayList<Answer>();
	}	
	
	ExecutorService pool = Executors.newFixedThreadPool(100);
	
	private JAXBContext contextProcess = JAXBContext.newInstance(Process.class);	
	private JAXBContext contextRoot = JAXBContext.newInstance(Root.class);
	
	private Answer answer() throws JAXBException, XMLStreamException, IOException {		
		Answer answer = new Answer();
		URL url = new URL("http://10.7.1.13:3000");
		Unmarshaller unmarshaller = contextProcess.createUnmarshaller();		
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLStreamReader reader = factory.createXMLStreamReader(url.openStream());		
		while(reader.hasNext()) {
			if (reader.getEventType() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("proc")) {
				Process process = unmarshaller.unmarshal(reader, Process.class).getValue();
				if (process.exec.contains("log"))
					answer.commands.add(new Command(process));
			}
			reader.next();
		}
		reader.close();
		return answer;
	}
	
	private Root root() throws InterruptedException, ExecutionException {		
		Root root = new Root();
		List<Future<Answer>> futures = new ArrayList<Future<Answer>>();		
		for (int i=0;i<3;i++) {
			futures.add(pool.submit(new Callable<Answer>() {
				public Answer call() throws Exception {
					return answer();
				}
			}));
		}
		for (Future<Answer> future : futures)
			root.answers.add(future.get());
		return root;
	}	
	
	private void encode(Root root, OutputStream response) throws JAXBException {
		Marshaller marshaller = contextRoot.createMarshaller();				
		marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		marshaller.marshal(root, response);
	}

	private ProxyApp() throws JAXBException, IOException {		
		HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
		server.setExecutor(pool);
		server.createContext("/", new HttpHandler() {
			public void handle(HttpExchange exchange) throws IOException {
				exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				OutputStream response = exchange.getResponseBody();
				try {
					encode(root(), response);
				} catch (JAXBException | InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
				response.close();
			}
		});
		server.start();
	}

	public static void main(String[] args) throws JAXBException, IOException {
		new ProxyApp();
	}

}

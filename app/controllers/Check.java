package controllers;

import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


import javax.inject.Inject;


import models.Util;
import play.Logger;
import play.libs.Json;
import play.libs.Jsonp;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.BodyParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Check extends Controller {
	@Inject HttpExecutionContext ec;
	@Inject Util util;
	
	// Classic HTML report, with optional score callback for Sunita
	public CompletableFuture<Result> checkHTML() throws IOException, InterruptedException {
		Map<String, String[]> params = request().body().asFormUrlEncoded();
		return CompletableFuture.supplyAsync(() -> {
			try {
		        Http.Cookie ccuCookie = request().cookie("ccu");
				String ccu = ccuCookie == null ? Util.createUID() : ccuCookie.value();					        

		        String repo = "ext";
		        String problem = "";
		        String level = "1";
				Path submissionDir = util.getDir("submissions");				
		        Path tempDir = Util.createTempDirectory(submissionDir);
		        
		        String callback = null;
		        for (String key : params.keySet()) {
		            String value = params.get(key)[0];
		            if (key.equals("repo"))
		                repo = value;
		            else if (key.equals("problem"))
		                problem = value;
		            else if (key.equals("level"))
		                level = value;
		            else if (key.equals("callback"))
		            	callback = value;
		            else
		                Util.write(tempDir, key, value);
		        }
		        util.runLabrat("html", repo, problem, level, tempDir.toAbsolutePath(), "User=" + ccu);
		        int age = 180 * 24 * 60 * 60;
		        Http.Cookie newCookie = Http.Cookie.builder("ccu", ccu).withMaxAge(age).build();

				if (callback != null) {
			        String report = Util.read(tempDir.resolve("report.html"));
		        	// Replace download link with Save Score button
		        	String target = "<p class=\"score\">";
		        	int n = report.indexOf(target) + target.length();
		        	int n2 = report.indexOf("<", n);
		        	String score = report.substring(n, n2);
		        	n = report.indexOf("<p class=\"download\">", n2);
		        	target = "</p>";
		        	n2 = report.indexOf(target, n) + target.length();
		        	String buttonHTML = "<input id='submitScore' type='button' value='Submit score'>";
		        	report = report.substring(0, n) + buttonHTML + report.substring(n2);
		        	
		        	
		        	String buttonScriptTemplate = "<script src=''https://code.jquery.com/jquery-2.2.0.min.js''></script>" +
		   "<script>$(document).ready(function() '{'" +
		    "$(''#submitScore'').click(function()'{'" +
		      "$.getJSON(''{0}?callback=?'', '{'  score: ''{1}'' '}')" +
		      ".done(function(data) '{'" +
		        "if (data.received) $(''#submitScore'').prop(''disabled'', true);" +
		      "'}');" +
		    "'}');" + 
		  "'}');" +      
		  "</script>";
		        	String buttonScript = MessageFormat.format(buttonScriptTemplate, callback, score);
		        	n = report.indexOf("<title>");
		        	report = report.substring(0, n) + buttonScript + report.substring(n);        	
			        return ok(report).withCookies(newCookie).as("text/html");
		        } 
		        else
		        	return redirect("fetch/" + tempDir.getFileName() + "/report.html").withCookies(newCookie);
			}
			catch (Exception ex) {
				return internalServerError(Util.getStackTrace(ex));
			}
		}, ec.current());        
        
        
        // TODO: Delete tempDir
	}
		
	// Request JSON, report html, txt, json
	@BodyParser.Of(BodyParser.Json.class)
	public Result checkJson() throws IOException, InterruptedException  {
		Path submissionDir = util.getDir("submissions");
        Path tempDir = Util.createTempDirectory(submissionDir);
	    JsonNode json = request().body().asJson();
	    Iterator<Map.Entry<String,JsonNode>> dirs = json.fields();
	    String repo = "ext";
	    String problem = null;
	    String level = "1";
	    String reportType = "json";
    	String uid = null;
	    while (dirs.hasNext()) {
	    	Map.Entry<String, JsonNode> dirEntry = dirs.next();
	    	String key = dirEntry.getKey();
	    	JsonNode value = dirEntry.getValue();
	    	if ("repo".equals(key)) repo = value.textValue();
	    	else if ("problem".equals(key)) problem = value.textValue();
	    	else if ("level".equals(key)) level = value.textValue();
	    	else if ("type".equals(key)) reportType = value.textValue();
	    	else if ("uid".equals(key)) uid = value.textValue();
	    	else {
	    		boolean encodeSolution = key.equals("c29sdXRpb24=");	    			
	    		Path dir = tempDir.resolve(encodeSolution ? "solution" : key);
	    		java.nio.file.Files.createDirectory(dir);
	    		Iterator<Map.Entry<String,JsonNode>> files = value.fields();
	    		while (files.hasNext()) {
	    			Map.Entry<String, JsonNode> fileEntry = files.next();
	    			String contents = fileEntry.getValue().textValue();
	    			if (encodeSolution) 
	    				contents = new String(Base64.getDecoder().decode(contents), "UTF-8");
	    			Util.write(dir, fileEntry.getKey(), contents);
	    		}
	    	}
	    }
	    if (problem == null) { // problem was submitted in JSON
               Logger.of("com.horstmann.codecheck.json").info("Request: " + json);
               if (uid == null)
            	   util.runLabrat(reportType, repo, problem, level, tempDir.toAbsolutePath(), tempDir.resolve("submission").toAbsolutePath());
               else
            	   util.runLabrat(reportType, repo, problem, level, tempDir.toAbsolutePath(), tempDir.resolve("submission").toAbsolutePath(), "uid=" + uid);
	    }
	    else
	    	util.runLabrat(reportType, repo, problem, level, tempDir.resolve("submission").toAbsolutePath());
	    if ("html".equals(reportType))
	    	return ok(Util.read(tempDir.resolve("submission/report.html"))).as("text/html");
	    else if ("text".equals(reportType))
	    	return ok(Util.read(tempDir.resolve("submission/report.txt"))).as("text/plain");
	    else {
               String result = Util.read(tempDir.resolve("submission/report.json"));
               Logger.of("com.horstmann.codecheck.json").info("Response: " + result);

               return ok(result).as("application/json");
            }
        // TODO: Delete tempDir	    
	}
	
	// From JS UI
	// TODO: Set cookie from files and pass it on as param?
	public CompletableFuture<Result> checkNJS() throws IOException, InterruptedException  {
		Map<String, String[]> params;
		if ("application/x-www-form-urlencoded".equals(request().contentType().orElse(""))) 
			params = request().body().asFormUrlEncoded();
		else 
			params = request().queryString();
		
		return CompletableFuture.supplyAsync(() -> {
			try {
				String repo = "ext";
				String problem = null;
				String level = "1";
				Path submissionDir = util.getDir("submissions");
				Path tempDir = Util.createTempDirectory(submissionDir);
				String reportType = "njs";
				String callback = null;
				String scoreCallback = null;
				Path dir = tempDir.resolve("submission");
				java.nio.file.Files.createDirectory(dir);
				StringBuilder requestParams = new StringBuilder();
				ObjectNode studentWork = JsonNodeFactory.instance.objectNode();
				for (String key : params.keySet()) {
					String value = params.get(key)[0];
					
					if (requestParams.length() > 0) requestParams.append(", ");
					requestParams.append(key);
					requestParams.append("=");
					int nl = value.indexOf('\n');
					if (nl >= 0) {
						requestParams.append(value.substring(0, nl));  
						requestParams.append("...");
					}
					else requestParams.append(value);
					
					if ("repo".equals(key)) repo = value;
					else if ("problem".equals(key)) problem = value;
					else if ("level".equals(key)) level = value;
					else if ("callback".equals(key)) callback = value;
					else if ("scoreCallback".equals(key)) scoreCallback = value;
					else {
						Util.write(dir, key, value);
						studentWork.put(key, value);
					}
				}
				Logger.of("com.horstmann.codecheck.check").info("checkNJS: " + requestParams);
				
				if (problem == null) // problem was submitted in JSON
					util.runLabrat(reportType, repo, problem, level, tempDir.toAbsolutePath(), tempDir.resolve("submission").toAbsolutePath());
				else
					util.runLabrat(reportType, repo, problem, level, tempDir.resolve("submission").toAbsolutePath());
				ObjectNode result = (ObjectNode) Json.parse(Util.read(tempDir.resolve("submission/report.json")));
				String reportZip = Util.base64(tempDir.resolve("submission"), "report.signed.zip");
				
				if (scoreCallback != null) {
					if (scoreCallback.startsWith("https://")) 
						scoreCallback = "http://" + scoreCallback.substring("https://".length()); // TODO: Fix
					
					//TODO: Add to result the student submissions
					ObjectNode augmentedResult = result.deepCopy();
					augmentedResult.set("studentWork", studentWork);
					
					String resultText = Json.stringify(augmentedResult);
					Logger.of("com.horstmann.codecheck.lti").info("Request: " + scoreCallback + " " + resultText);
					String response = Util.httpPost(scoreCallback, resultText, "application/json");
					Logger.of("com.horstmann.codecheck.lti").info("Response: " + response);
				}
				
				result.put("zip", reportZip);
				if (callback == null)
					return ok(result);
				else
					return ok(Jsonp.jsonp(callback, result)); // TODO: Include "zip" here?
			} catch (Exception ex) {
				return internalServerError(Util.getStackTrace(ex));
			}
			// TODO: Delete tempDir	unless flag is set to keep it?	
		}, ec.current());		
	
	}
}

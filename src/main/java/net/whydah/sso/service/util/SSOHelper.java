package net.whydah.sso.service.util;

import com.restfb.types.User;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import net.whydah.sso.service.config.AppConfig;
import net.whydah.sso.service.data.ApplicationCredential;
import net.whydah.sso.service.data.UserCredential;
import net.whydah.sso.service.data.WhydahUserTokenId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;

public class SSOHelper {
    public static final String USER_TOKEN_REFERENCE_NAME = "whydahusertoken";
    public static final String USERTICKET = "userticket";
    public static final String USER_TOKEN_ID = "usertokenid";
    private static final Logger logger = LoggerFactory.getLogger(SSOHelper.class);

    private final Client tokenServiceClient = Client.create();
    private final URI tokenServiceUri;
    private String myAppTokenXml;
    private String myAppTokenId;

    public SSOHelper() {
        try {
            tokenServiceUri = UriBuilder.fromUri(AppConfig.readProperties().getProperty("securitytokenservice")).build();
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getLocalizedMessage(), e);
        }
    }

    public Cookie createUserTokenCookie(String userTokenXml) {
        String tokenID = getTokenId(userTokenXml);
        Cookie cookie = new Cookie(USER_TOKEN_REFERENCE_NAME, tokenID);
        int maxAge = calculateTokenRemainingLifetime(userTokenXml);
        // cookie.setMaxAge(maxAge);
        cookie.setMaxAge(365 * 24 * 60 * 60);
        cookie.setPath("/");
        // cookie.setDomain("ssologinservice.yenka.freecode.no");
        cookie.setValue(tokenID);
        // cookie.setSecure(true);
        logger.debug("Created cookie with name=" + cookie.getName() + ", tokenID=" + cookie.getValue() + ", maxAge=" + cookie.getMaxAge());
        return cookie;
    }
    private String getTokenId(String userTokenXml) {
        if (userTokenXml == null) {
            logger.debug("Empty  userToken");
            return "";
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(userTokenXml)));
            XPath xPath = XPathFactory.newInstance().newXPath();

            String expression = "/token/@id";
            XPathExpression xPathExpression = xPath.compile(expression);
            return (xPathExpression.evaluate(doc));
        } catch (Exception e) {
            logger.error("", e);
        }
        return "";
    }
    private int calculateTokenRemainingLifetime(String userxml) {
        int tokenLifespan = Integer.parseInt(getLifespan(userxml));
        long tokenTimestamp = Long.parseLong(getTimestamp(userxml));
        long endOfTokenLife = tokenTimestamp + tokenLifespan;
        long remainingLife_ms = endOfTokenLife - System.currentTimeMillis();
        return (int)remainingLife_ms/1000;
    }

    private String getLifespan(String userTokenXml) {
        if (userTokenXml == null){
            logger.debug("Empty  userToken");
            return "";
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(userTokenXml)));
            XPath xPath = XPathFactory.newInstance().newXPath();

            String expression = "/token/lifespan";
            XPathExpression xPathExpression = xPath.compile(expression);
            return (xPathExpression.evaluate(doc));
        } catch (Exception e) {
            logger.error("", e);
        }
        return "";
    }

    private String getTimestamp(String userTokenXml) {
        if (userTokenXml==null){
            logger.debug("Empty  userToken");
            return "";
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(userTokenXml)));
            XPath xPath = XPathFactory.newInstance().newXPath();

            String expression = "/token/timestamp";
            XPathExpression xPathExpression = xPath.compile(expression);
            return (xPathExpression.evaluate(doc));
        } catch (Exception e) {
            logger.error("", e);
        }
        return "";
    }

    public String appendTicketIDToRedirectURI(String redirectURI, WhydahUserTokenId usertokenid) {
        String usertokenidAsString = usertokenid.getUsertokenid();
        return appendTicketToRedirectURI(redirectURI, usertokenidAsString);
    }

    public String appendTokenIDToRedirectURI(String redirectURI, String usertokenId) {
        char paramSep = redirectURI.contains("?") ? '&' : '?';
        redirectURI += paramSep + SSOHelper.USER_TOKEN_ID + '=' + usertokenId;
        return redirectURI;
    }

    public String appendTicketToRedirectURI(String redirectURI, String ticket) {
        char paramSep = redirectURI.contains("?") ? '&' : '?';
        redirectURI += paramSep + SSOHelper.USERTICKET + '=' + ticket;
        return redirectURI;
    }

    public WhydahUserTokenId getTokenidFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            logger.debug("Found {} cookie(s)", cookies.length);
            for (Cookie cookie : cookies) {
                logger.debug("Checking cookie:"+cookie.getName());
                if (!SSOHelper.USER_TOKEN_REFERENCE_NAME.equals(cookie.getName())) {
                    continue;
                }

                String usertokenId = cookie.getValue();
                logger.debug("Found whydahusertoken cookie, whydahusertokenID={}", usertokenId);
                if (verifyUserTokenId(usertokenId)) {
                    logger.debug("whydahusertoken ok");
                    return WhydahUserTokenId.fromTokenId(usertokenId);
                } else {
                    logger.debug("whydahusertoken validation failed");
                    return WhydahUserTokenId.invalidTokenId();
                }
            }
        }
        logger.debug("Fikk ingen cookies");
        return WhydahUserTokenId.invalidTokenId();
    }


    private void logonApplication() {
        //todo sjekke om myAppTokenXml er gyldig før reauth
        WebResource logonResource = tokenServiceClient.resource(tokenServiceUri).path("logon");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        ApplicationCredential appCredential = new ApplicationCredential();
        appCredential.setApplicationID("Whydah SSO LoginService");
        appCredential.setApplicationPassord("secret dummy");

        formData.add("applicationcredential", appCredential.toXML());
        ClientResponse response = logonResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        //todo håndtere feil i statuskode + feil ved app-pålogging (retry etc)
        if (response.getStatus() != 200) {
            logger.error("Application authentication failed with statuscode {}", response.getStatus());
            throw new RuntimeException("Application authentication failed");
        }
        myAppTokenXml = response.getEntity(String.class);
        myAppTokenId = getTokenIdFromAppToken(myAppTokenXml);
        logger.debug("Applogon ok: apptokenxml: {}", myAppTokenXml);
        logger.debug("myAppTokenId: {}", myAppTokenId);
    }

    private String getTokenIdFromAppToken(String appTokenXML) {
        return appTokenXML.substring(appTokenXML.indexOf("<applicationtoken>") + "<applicationtoken>".length(), appTokenXML.indexOf("</applicationtoken>"));
    }

    public String getUserToken(UserCredential user, String ticket) {
        logonApplication();
        logger.debug("apptokenid: {}", myAppTokenId);

        logger.debug("Log on with user credentials {}", user.toString());
        WebResource getUserToken = tokenServiceClient.resource(tokenServiceUri).path("iam/" + myAppTokenId + "/" + ticket + "/usertoken");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        formData.add("apptoken", myAppTokenXml);
        formData.add("usercredential", user.toXML());
        ClientResponse response = getUserToken.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.FORBIDDEN.getStatusCode()) {
            logger.info("User authentication failed with status code " + response.getStatus());
            return null;
            //throw new IllegalArgumentException("Log on failed. " + ClientResponse.Status.FORBIDDEN);
        }
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.debug("Log on OK with response {}", responseXML);
            return responseXML;
        }

        //retry once for other statuses
        response = getUserToken.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.debug("Log on OK with response {}", responseXML);
            return responseXML;
        }
        logger.info("User authentication failed with status code " + response.getStatus());
        return null;
        //throw new RuntimeException("User authentication failed with status code " + response.getStatus());
    }

    public String getUserTokenByTicket(String ticket) {
        logonApplication();

        WebResource userTokenResource = tokenServiceClient.resource(tokenServiceUri).path("iam/" + myAppTokenId + "/getusertokenbyticketid");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        formData.add("apptoken", myAppTokenXml);
        formData.add("ticketid", ticket);
        ClientResponse response = userTokenResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.FORBIDDEN.getStatusCode()) {
            throw new IllegalArgumentException("Login failed.");
        }
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.debug("Response OK with XML: {}", responseXML);
            return responseXML;
        }
        //retry
        response = userTokenResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.debug("Response OK with XML: {}", responseXML);
            return responseXML;
        }
        logger.warn("User authentication failed: {}", response);

        throw new RuntimeException("User authentication failed with status code " + response.getStatus());
    }

    public void releaseUserToken(String userTokenId) {
        logonApplication();
        WebResource releaseResource = tokenServiceClient.resource(tokenServiceUri).path("iam/" + myAppTokenId + "/releaseusertoken");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        formData.add(USER_TOKEN_ID, userTokenId);
        ClientResponse response = releaseResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if(response.getStatus() != ClientResponse.Status.OK.getStatusCode()) {
            logger.warn("releaseUserToken failed: {}", response);
        }
    }

    public boolean verifyUserTokenId(String usertokenid) {
        logonApplication();
        WebResource verifyResource = tokenServiceClient.resource(tokenServiceUri).path("iam/" + myAppTokenId + "/validateusertokenid/" + usertokenid);
        ClientResponse response = verifyResource.get(ClientResponse.class);
        if(response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            logger.debug("token validated");
            return true;
        }
        if(response.getStatus() == ClientResponse.Status.CONFLICT.getStatusCode()) {
            logger.debug("token not ok: {}" + response);
            return false;
        }
        //retry
        logonApplication();
        response = verifyResource.get(ClientResponse.class);
        return response.getStatus() == ClientResponse.Status.OK.getStatusCode();
    }

    public String createAndLogonUser(User fbUser, String fbAccessToken, UserCredential userCredential, String ticket) {
        logonApplication();
        logger.debug("apptokenid: {}", myAppTokenId);

        WebResource createUserResource = tokenServiceClient.resource(tokenServiceUri).path("iam/" + myAppTokenId +"/"+ ticket + "/createuser");

        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        formData.add("apptoken", myAppTokenXml);
        formData.add("usercredential", userCredential.toXML());
        String facebookUserAsXml = FacebookHelper.getFacebookUserAsXml(fbUser, fbAccessToken);
        formData.add("fbuser", facebookUserAsXml);
        logger.debug("createAndLogonUser with fbuser XML: " + facebookUserAsXml);
        logger.info("createAndLogonUser username=" + fbUser.getUsername());
        ClientResponse response = createUserResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);

        //No need to retry if we know it is forbidden.
        if (response.getStatus() == ClientResponse.Status.FORBIDDEN.getStatusCode()) {
            //throw new IllegalArgumentException("createAndLogonUser failed. username=" + fbUser.getUsername() + ", id=" + fbUser.getId());
            logger.warn("createAndLogonUser failed. username=" + fbUser.getUsername() + ", id=" + fbUser.getId());
            return null;
        }
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.debug("createAndLogonUser OK with response {}", responseXML);
            return responseXML;
        }

        //retry once for other statuses
        response = createUserResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.debug("createAndLogonUser OK with response {}", responseXML);
            return responseXML;
        }

        logger.warn("createAndLogonUser failed after retrying once.");
        return null;
        //throw new RuntimeException("createAndLogonUser failed with status code " + response.getStatus());
    }



}

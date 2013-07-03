package net.whydah.sso.service;

import net.whydah.sso.service.data.UserCredential;
import net.whydah.sso.service.data.UserNameAndPasswordCredential;
import net.whydah.sso.service.data.WhydahUserTokenId;
import net.whydah.sso.service.util.SSOHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

@Controller
public class SSOLoginController {
    private final static Logger logger = LoggerFactory.getLogger(SSOLoginController.class);
    public static final String DEFAULT_REDIRECT = "welcome";
    private final SSOHelper ssoHelper = new SSOHelper();

    /**
     * Kalles når påloggingssiden skal vises.
     * @param request Http-request
     * @param model Inneholder data til template
     * @return template som skal vises.
     */
    @RequestMapping("/login")
    public String login(HttpServletRequest request, Model model) {
        String redirectURI = getRedirectURI(request);

        WhydahUserTokenId usertokenId = ssoHelper.getTokenidFromCookie(request);
        if (usertokenId.isValid()) {
            redirectURI = ssoHelper.appendTokenIDToRedirectURI(redirectURI, usertokenId.getUsertokenid());
            model.addAttribute("redirect", redirectURI);
            logger.info("Redirecting to {}", redirectURI);
            return "action";
        }

        model.addAttribute("redirectURI", redirectURI);
        return "login";
    }

    private String getRedirectURI(HttpServletRequest request) {
        String redirectURI = request.getParameter("redirectURI");
        logger.debug("redirectURI from request: {}", redirectURI);
        if (redirectURI == null || redirectURI.length() < 4) {
            logger.debug("No redirectURI found, setting to {}", DEFAULT_REDIRECT);
            return DEFAULT_REDIRECT;
        }
        return redirectURI;
    }



    @RequestMapping("/welcome")
    public String welcome(HttpServletRequest request, Model model) {
        String userTicket = request.getParameter(SSOHelper.USERTICKET);
        if (userTicket != null && userTicket.length() > 3) {
            model.addAttribute("TicketID", userTicket);
            //model.addAttribute("Token", ssoHelper.getUserTokenByTicket(userticket));
        }

        String userTokenId = request.getParameter(SSOHelper.USER_TOKEN_ID);
        if (userTokenId != null && userTokenId.length() > 3) {
            model.addAttribute("TokenID", userTokenId);
        }

        return "welcome";
    }

    @RequestMapping("/action")
    public String action(HttpServletRequest request, HttpServletResponse response, Model model) {
        UserCredential user = new UserNameAndPasswordCredential(request.getParameter("user"), request.getParameter("password"));

        String redirectURI = getRedirectURI(request);

        String ticketID = UUID.randomUUID().toString();
        String userTokenXml = ssoHelper.getUserToken(user, ticketID);

        if (userTokenXml == null) {
            logger.info("getUserToken failed. Redirecting to login.");
            model.addAttribute("redirectURI", redirectURI);
            model.addAttribute("loginError", "Could not log in.");
            return "login";
        }


        Cookie cookie = ssoHelper.createUserTokenCookie(userTokenXml);
        // cookie.setDomain("whydah.net");
        response.addCookie(cookie);

        redirectURI = ssoHelper.appendTicketToRedirectURI(redirectURI, ticketID);
        model.addAttribute("redirect", redirectURI);
        return "action";
    }
}
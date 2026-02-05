package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.ClamavClient;

@Service
public class ClamavClientProvider {

    public ClamavClient clientFor(ClamdEndpoint ep) {
        return new ClamavClient(ep.getHost(), ep.getPort(), ep.getPlatform());
    }
}

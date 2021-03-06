package hudson.plugins.jira.extension;

import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClient;
import com.atlassian.jira.rest.client.internal.async.DisposableHttpClient;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;

public class ExtendedAsynchronousJiraRestClient extends AsynchronousJiraRestClient implements ExtendedJiraRestClient {
    private final ExtendedVersionRestClient extendedVersionRestClient;
    private final ExtendedAsynchronousIssueRestClient extendedAsynchronousIssueRestClient;

    public ExtendedAsynchronousJiraRestClient(URI serverUri, DisposableHttpClient httpClient) {
        super(serverUri, httpClient);
        final URI baseUri = UriBuilder.fromUri(serverUri).path("/rest/api/latest").build();
        extendedVersionRestClient = new ExtendedAsynchronousVersionRestClient(baseUri, httpClient);
        extendedAsynchronousIssueRestClient = new ExtendedAsynchronousIssueRestClient(baseUri, httpClient, this.getSessionClient(),
                                                                                      this.getMetadataClient());
    }

    @Override
    public ExtendedVersionRestClient getExtendedVersionRestClient() {
        return extendedVersionRestClient;
    }

    @Override
    public ExtendedAsynchronousIssueRestClient getExtendedAsynchronousIssueRestClient() {
        return extendedAsynchronousIssueRestClient;
    }
}
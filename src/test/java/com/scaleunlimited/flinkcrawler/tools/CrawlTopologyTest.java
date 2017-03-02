package com.scaleunlimited.flinkcrawler.tools;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleunlimited.flinkcrawler.crawldb.InMemoryCrawlDB;
import com.scaleunlimited.flinkcrawler.fetcher.MockRobotsFetcher;
import com.scaleunlimited.flinkcrawler.fetcher.WebGraphFetcher;
import com.scaleunlimited.flinkcrawler.functions.CheckUrlWithRobotsFunction;
import com.scaleunlimited.flinkcrawler.functions.CrawlDBFunction;
import com.scaleunlimited.flinkcrawler.functions.FetchUrlsFunction;
import com.scaleunlimited.flinkcrawler.functions.ParseFunction;
import com.scaleunlimited.flinkcrawler.parser.SimplePageParser;
import com.scaleunlimited.flinkcrawler.parser.SimpleSiteMapParser;
import com.scaleunlimited.flinkcrawler.pojos.BaseUrl;
import com.scaleunlimited.flinkcrawler.pojos.FetchStatus;
import com.scaleunlimited.flinkcrawler.pojos.ParsedUrl;
import com.scaleunlimited.flinkcrawler.sources.SeedUrlSource;
import com.scaleunlimited.flinkcrawler.tools.CrawlTopology.CrawlTopologyBuilder;
import com.scaleunlimited.flinkcrawler.urls.SimpleUrlLengthener;
import com.scaleunlimited.flinkcrawler.urls.SimpleUrlNormalizer;
import com.scaleunlimited.flinkcrawler.urls.SimpleUrlValidator;
import com.scaleunlimited.flinkcrawler.utils.UrlLogger;
import com.scaleunlimited.flinkcrawler.utils.UrlLoggerImpl.UrlLoggerResults;
import com.scaleunlimited.flinkcrawler.webgraph.SimpleWebGraph;

import crawlercommons.robots.SimpleRobotRulesParser;

public class CrawlTopologyTest {
	static final Logger LOGGER = LoggerFactory.getLogger(CrawlTopologyTest.class);
	
    private static final String CRLF = "\r\n";

	@Test
	public void test() throws Exception {
		LocalStreamEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();

		SimpleUrlNormalizer normalizer = new SimpleUrlNormalizer();
		SimpleWebGraph graph = new SimpleWebGraph(normalizer)
			.add("domain1.com", "domain1.com/page1", "domain1.com/page2", "domain1.com/blocked")
			.add("domain1.com/page1")
			.add("domain1.com/page2", "domain2.com", "domain1.com", "domain1.com/page1")
			.add("domain2.com", "domain2.com/page1", "domain3.com")
			.add("domain3.com", "domain3.com/page1");

		Map<String, String> robotPages = new HashMap<String, String>();
		// Block one page, and set no crawl delay.
		robotPages.put("http://domain1.com:80/robots.txt", "User-agent: *" + CRLF + "Disallow: /blocked" + CRLF + "Crawl-delay: 0" + CRLF);
		
		// Set a long crawl delay.
		robotPages.put("http://domain3.com:80/robots.txt", "User-agent: *" + CRLF + "Crawl-delay: 30" + CRLF);

		CrawlTopologyBuilder builder = new CrawlTopologyBuilder(env)
			.setUrlSource(new SeedUrlSource(1.0f, "http://domain1.com"))
			.setUrlLengthener(new SimpleUrlLengthener())
			.setCrawlDB(new InMemoryCrawlDB())
			.setRobotsFetcherBuilder(new MockRobotsFetcher.MockRobotsFetcherBuilder(new MockRobotsFetcher(robotPages)))
			.setRobotsParser(new SimpleRobotRulesParser())
			.setPageParser(new SimplePageParser())
			.setContentSink(new DiscardingSink<ParsedUrl>())
			.setUrlNormalizer(normalizer)
			.setUrlFilter(new SimpleUrlValidator())
			.setSiteMapFetcherBuilder(new WebGraphFetcher.WebGraphFetcherBuilder(new WebGraphFetcher(graph)))
			.setSiteMapParser(new SimpleSiteMapParser())
			// You can increase this value from 10000 to say 100000 if you need time inside of a threaded
			// executor before the cluster terminates.
			.setMaxWaitTime(10000)
			.setDefaultCrawlDelay(0)
			.setPageFetcherBuilder(new WebGraphFetcher.WebGraphFetcherBuilder(new WebGraphFetcher(graph)));

		CrawlTopology ct = builder.build();
		
		File testDir = new File("target/CrawlTopologyTest/");
		testDir.mkdirs();
		File dotFile = new File(testDir, "topology.dot");
		ct.printDotFile(dotFile);
		
		ct.execute();
		
		for (Tuple3<Class<?>, BaseUrl, Map<String, String>> entry : UrlLogger.getLog()) {
			LOGGER.info(String.format("%s: %s", entry.f0, entry.f1));
		}
		
		String domain1page1 = normalizer.normalize("domain1.com/page1");
		String domain1page2 = normalizer.normalize("domain1.com/page2");
		String domain2page1 = normalizer.normalize("domain2.com/page1");
		String domain1blockedPage = normalizer.normalize("domain1.com/blocked");
		String domain3deferredPage = normalizer.normalize("domain3.com/page1");
		
		UrlLoggerResults results = new UrlLoggerResults(UrlLogger.getLog());
		
		results
			.assertUrlLoggedBy(CheckUrlWithRobotsFunction.class, domain1page1, 1)
			.assertUrlLoggedBy(FetchUrlsFunction.class, domain1page1, 1)
			.assertUrlLoggedBy(	CrawlDBFunction.class, domain1page1, 1,
								FetchStatus.class.getSimpleName(), FetchStatus.FETCHED.toString())
			.assertUrlLoggedBy(ParseFunction.class, domain1page1)
			
			.assertUrlLoggedBy(CheckUrlWithRobotsFunction.class, domain1page2, 1)
			.assertUrlLoggedBy(FetchUrlsFunction.class, domain1page2, 1)
			.assertUrlLoggedBy(	CrawlDBFunction.class, domain1page2, 1,
								FetchStatus.class.getSimpleName(), FetchStatus.FETCHED.toString())
			.assertUrlLoggedBy(ParseFunction.class, domain1page2)
			
			.assertUrlLoggedBy(CheckUrlWithRobotsFunction.class, domain2page1, 1)
			.assertUrlLoggedBy(FetchUrlsFunction.class, domain2page1, 1)
			.assertUrlLoggedBy(	CrawlDBFunction.class, domain2page1, 1,
								FetchStatus.class.getSimpleName(), FetchStatus.HTTP_NOT_FOUND.toString())
			.assertUrlNotLoggedBy(ParseFunction.class, domain2page1)
			
			// domain3.com/page1 should be skipped due to crawl-delay.
			.assertUrlLoggedBy(CheckUrlWithRobotsFunction.class, domain3deferredPage, 1)
			.assertUrlLoggedBy(FetchUrlsFunction.class, domain3deferredPage, 1)
			.assertUrlLoggedBy(	CrawlDBFunction.class, domain3deferredPage, 1,
								FetchStatus.class.getSimpleName(), FetchStatus.SKIPPED_CRAWLDELAY.toString())
			.assertUrlNotLoggedBy(ParseFunction.class, domain3deferredPage)

			.assertUrlLoggedBy(CheckUrlWithRobotsFunction.class, domain1blockedPage)
			.assertUrlNotLoggedBy(FetchUrlsFunction.class, domain1blockedPage)
			.assertUrlNotLoggedBy(ParseFunction.class, domain1blockedPage)
			.assertUrlLoggedBy(CrawlDBFunction.class, domain1blockedPage, 2);
	}
}

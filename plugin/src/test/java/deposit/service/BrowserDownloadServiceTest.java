package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BrowserDownloadServiceTest {
    @Test void resolvesOpenEndedAndSuffixRanges(){
        assertEquals(new BrowserDownloadService.Range(0,10,false),BrowserDownloadService.parseRange(null,10));
        assertEquals(new BrowserDownloadService.Range(3,4,true),BrowserDownloadService.parseRange("bytes=3-6",10));
        assertEquals(new BrowserDownloadService.Range(7,3,true),BrowserDownloadService.parseRange("bytes=7-",10));
        assertEquals(new BrowserDownloadService.Range(7,3,true),BrowserDownloadService.parseRange("bytes=-3",10));
        assertEquals(new BrowserDownloadService.Range(0,10,true),BrowserDownloadService.parseRange("bytes=0-99",10));
    }

    @Test void rejectsEmptyMultipleAndMalformedRangesWithActualSize(){
        for(String header:new String[]{"bytes=0-1,4-5","bytes=-0","bytes=5-2","items=0-1","bytes=999999999999999999999-"}){
            var failure=assertThrows(BrowserDownloadService.DownloadFailure.class,()->BrowserDownloadService.parseRange(header,5));
            assertEquals(416,failure.status);assertEquals(5,failure.rangeSize);
        }
        var empty=assertThrows(BrowserDownloadService.DownloadFailure.class,()->BrowserDownloadService.parseRange("bytes=0-",0));assertEquals(0,empty.rangeSize);
    }
}

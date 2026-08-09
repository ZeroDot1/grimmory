package org.booklore.service.bookdrop;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.BookdropFinalizeRequest;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.file.FileMovingHelper;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookDropServiceFinalizeTest {

    @Mock
    private BookdropFileRepository bookdropFileRepository;
    @Mock
    private BookdropMonitoringService bookdropMonitoringService;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private MonitoringRegistrationService monitoringRegistrationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private FileMovingHelper fileMovingHelper;
    @Mock
    private AppProperties appProperties;
    @Mock
    private BookdropNotificationService bookdropNotificationService;
    @Mock
    private KoboAutoShelfService koboAutoShelfService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    @InjectMocks
    private BookDropService bookDropService;

    @Test
    void finalizeImport_selectAll_emptyExcludedIds_shouldCallFindAllIds() {
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(true);
        request.setExcludedIds(Collections.emptyList());
        request.setDefaultLibraryId(1L);
        request.setDefaultPathId(1L);

        when(bookdropFileRepository.findAllIds()).thenReturn(List.of(1L, 2L));
        when(bookdropFileRepository.findAllById(anyList())).thenReturn(Collections.emptyList()); // Mock chunk processing

        bookDropService.finalizeImport(request);

        verify(bookdropFileRepository).findAllIds();
        verify(bookdropFileRepository, never()).findAllExcludingIdsFlat(anyList());
    }

    @Test
    void finalizeImport_selectAll_withExcludedIds_shouldCallFindAllExcludingIdsFlat() {
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(true);
        request.setExcludedIds(List.of(3L));
        request.setDefaultLibraryId(1L);
        request.setDefaultPathId(1L);

        when(bookdropFileRepository.findAllExcludingIdsFlat(anyList())).thenReturn(List.of(1L, 2L));
        when(bookdropFileRepository.findAllById(anyList())).thenReturn(Collections.emptyList()); // Mock chunk processing

        bookDropService.finalizeImport(request);

        verify(bookdropFileRepository).findAllExcludingIdsFlat(List.of(3L));
        verify(bookdropFileRepository, never()).findAllIds();
    }

    @Test
    void finalizeImport_commitFlushFailure_shouldNotThrowAndMarkFileFailed() {
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(false);
        request.setDefaultLibraryId(1L);
        request.setDefaultPathId(1L);
        BookMetadata metadata = new BookMetadata();
        metadata.setTitle("Test Book");
        BookdropFinalizeRequest.BookdropFinalizeFile fileReq = new BookdropFinalizeRequest.BookdropFinalizeFile();
        fileReq.setFileId(1L);
        fileReq.setLibraryId(1L);
        fileReq.setPathId(1L);
        fileReq.setMetadata(metadata);
        request.setFiles(List.of(fileReq));

        BookdropFileEntity file = BookdropFileEntity.builder()
                .id(1L)
                .fileName("test.epub")
                .filePath("/nonexistent/test.epub")
                .build();

        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .id(1L)
                .path("/tmp")
                .build();
        LibraryEntity library = LibraryEntity.builder()
                .name("Test Library")
                .libraryPaths(List.of(libraryPath))
                .build();

        when(bookdropFileRepository.findAllById(anyList())).thenReturn(List.of(file));
        when(libraryRepository.findByIdWithPaths(1L)).thenReturn(java.util.Optional.of(library));
        when(fileMovingHelper.getFileNamingPattern(library)).thenReturn("{currentFilename}");
        when(fileMovingHelper.generateNewFilePath(anyString(), any(BookMetadata.class), anyString(), anyString()))
                .thenReturn(Path.of("/tmp/test.epub"));
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(new RuntimeException("simulated flush failure")).when(transactionManager).commit(any());

        var result = bookDropService.finalizeImport(request);

        assertEquals(1, result.getFailed());
        assertEquals(1, result.getTotalFiles());
        assertTrue(result.getResults().isEmpty());
        verify(transactionManager).getTransaction(any());
        verify(notificationService).sendMessage(eq(Topic.LOG), contains("Error finalizing file"));
    }

    @Test
    void finalizeImport_selectAll_largeIdList_shouldChunkFindAllByIdQueries() {
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(true);
        request.setExcludedIds(Collections.emptyList());
        request.setDefaultLibraryId(1L);
        request.setDefaultPathId(1L);

        List<Long> ids = IntStream.range(0, 150).mapToLong(i -> i).boxed().toList();
        when(bookdropFileRepository.findAllIds()).thenReturn(ids);
        when(bookdropFileRepository.findAllById(anyList())).thenReturn(Collections.emptyList());

        bookDropService.finalizeImport(request);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(bookdropFileRepository, atLeast(3)).findAllById(captor.capture());
        for (List<?> chunk : captor.getAllValues()) {
            assertTrue(chunk.size() <= 100);
        }
    }
}

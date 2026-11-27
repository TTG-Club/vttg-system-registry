package club.ttg.systemregistry.catalog;

import club.ttg.systemregistry.catalog.api.CatalogSystemResponse;
import club.ttg.systemregistry.common.InvalidRequestParameterException;
import club.ttg.systemregistry.manifest.Versions;
import club.ttg.systemregistry.submission.SystemSubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CatalogService {

    private final SystemSubmissionRepository repository;

    public CatalogService(SystemSubmissionRepository repository) {
        this.repository = repository;
    }

    /**
     * Одобренные системы; с версией VTTG — только совместимые с ней. Систем
     * немного, поэтому совместимость считаем в памяти: версии сравниваются по
     * числам, а не строками, и в SQL это неудобно.
     */
    @Transactional(readOnly = true)
    public List<CatalogSystemResponse> find(String vttgVersion) {
        var systems = repository.findApproved();
        if (vttgVersion == null || vttgVersion.isBlank()) {
            return systems.stream().map(CatalogSystemResponse::from).toList();
        }

        String version = vttgVersion.strip();
        if (!Versions.LOOSE.matcher(version).matches()) {
            throw new InvalidRequestParameterException("vttgVersion: нужна версия, например 0.9.266");
        }
        return systems.stream()
                .filter(system -> system.getCompatibility().accepts(version))
                .map(CatalogSystemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CatalogSystemResponse findById(String systemId) {
        return repository.findApprovedBySystemId(systemId)
                .map(CatalogSystemResponse::from)
                .orElseThrow(() -> new CatalogSystemNotFoundException(systemId));
    }
}

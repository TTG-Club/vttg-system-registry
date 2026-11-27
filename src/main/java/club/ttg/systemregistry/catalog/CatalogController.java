package club.ttg.systemregistry.catalog;

import club.ttg.systemregistry.catalog.api.CatalogSystemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Каталог одобренных систем — его читает VTTG и форма заявки модуля. */
@RestController
@RequestMapping("/api/v1/systems")
@Tag(name = "Каталог систем")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Одобренные системы",
            description = "С параметром vttgVersion — только совместимые с этой версией VTTG "
                    + "(по compatibility.minimum/maximum из system.json)")
    public List<CatalogSystemResponse> find(
            @Parameter(description = "версия VTTG, например 0.9.266")
            @RequestParam(required = false) String vttgVersion
    ) {
        return service.find(vttgVersion);
    }

    @GetMapping("/{systemId}")
    @Operation(summary = "Одобренная система по id из system.json")
    public CatalogSystemResponse findById(@PathVariable String systemId) {
        return service.findById(systemId);
    }
}

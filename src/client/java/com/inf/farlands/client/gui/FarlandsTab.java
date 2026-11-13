package com.inf.farlands.client.gui;

import java.util.List;
import java.util.Map;

import com.inf.farlands.client.gui.page.DimensionTab;
import com.inf.farlands.client.gui.page.FamilySelectionStore;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemPageHooks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 创建世界页的边境之地页签：每个维度一页，五族各一个下拉与它的参数框。
 *
 * <p>
 * 骨架全在 {@link DimensionTab}，这里只答四件事：页标题、五族、族选择存在 {@link CreatingWorldSystemsConfig}、
 * 进页时触发 {@link SystemPageHooks}。维度表在建页之前登记进暂存处，界面建页与落盘编 {@code SystemsData}
 * 读的是同一份，两处各取各的会静默丢配置。
 */
public class FarlandsTab extends DimensionTab {

    /** 族选择落在创建世界的暂存处，与落盘那条路共用同一份状态。 */
    private static final FamilySelectionStore STORE = new FamilySelectionStore() {

        @Override
        public SystemId system(Identifier page, FamilyKind kind) {
            return CreatingWorldSystemsConfig.system(page, kind);
        }

        @Override
        public void select(Identifier page, FamilyKind kind, SystemId system) {
            CreatingWorldSystemsConfig.select(page, kind, system);
        }

        @Override
        public Map<String, String> text(Identifier page, FamilyKind kind, SystemId system) {
            return CreatingWorldSystemsConfig.text(page, kind, system);
        }

        @Override
        public void putText(Identifier page, FamilyKind kind, SystemId system, String key, String value) {
            CreatingWorldSystemsConfig.putText(page, kind, system, key, value);
        }
    };

    public FarlandsTab(List<Identifier> dimensions) {
        super(dimensions);
    }

    @Override
    protected void onInit(List<Identifier> dimensions) {
        CreatingWorldSystemsConfig.setDimensions(dimensions);
    }

    @Override
    protected Component tabTitle() {
        return Component.translatable("createWorld.tab.infs-farlands.title");
    }

    @Override
    protected List<FamilyKind> families() {
        return CreatingWorldSystemsConfig.families();
    }

    @Override
    protected FamilySelectionStore store() {
        return STORE;
    }

    @Override
    protected List<SystemId> ids(FamilyKind kind) {
        return switch (kind) {
            case BIOME -> com.inf.farlands.terrain.registry.SystemRegistries.biomeIds();
            case TERRAIN -> com.inf.farlands.terrain.registry.SystemRegistries.terrainIds();
            case SURFACE -> com.inf.farlands.terrain.registry.SystemRegistries.surfaceIds();
            case CARVER -> com.inf.farlands.terrain.registry.SystemRegistries.carverIds();
            case DECORATION -> com.inf.farlands.terrain.registry.SystemRegistries.decorationIds();
        };
    }

    @Override
    protected java.util.function.Function<SystemId, Class<?>> classOf(FamilyKind kind) {
        return switch (kind) {
            case BIOME -> com.inf.farlands.terrain.registry.SystemRegistries::biomeClassOf;
            case TERRAIN -> com.inf.farlands.terrain.registry.SystemRegistries::terrainClassOf;
            case SURFACE -> com.inf.farlands.terrain.registry.SystemRegistries::surfaceClassOf;
            case CARVER -> com.inf.farlands.terrain.registry.SystemRegistries::carverClassOf;
            case DECORATION -> com.inf.farlands.terrain.registry.SystemRegistries::decorationClassOf;
        };
    }

    @Override
    protected SystemId defaultId(Identifier page, FamilyKind kind) {
        return com.inf.farlands.terrain.registry.SystemsIO.defaultId(page, kind);
    }

    @Override
    protected void onPageEntered(Identifier dimension) {
        SystemPageHooks.fireEntered(dimension);
    }
}

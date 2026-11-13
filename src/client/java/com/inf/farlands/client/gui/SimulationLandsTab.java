package com.inf.farlands.client.gui;

import java.util.List;

import com.inf.farlands.client.gui.page.DimensionTab;
import com.inf.farlands.client.gui.page.FamilySelectionStore;
import com.inf.farlands.client.gui.page.InMemorySelectionStore;
import com.inf.farlands.terrain.registry.FamilyKind;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 创建世界页的模拟之地页签：与边境之地同级，每个维度一页，每页只有地形一族。
 *
 * <p>
 * 内容与边境之地同源：选项、标题、默认项都经 {@link DimensionTab} 那一份，族行的校验链也同一条，所以参数改坏
 * 会照常标红。区别只有两处：族少四个，选择存在 {@link InMemorySelectionStore} 里，不进 {@code systems.dat}，
 * 也不触发进页钩子。因此新建世界的结果不受本页签影响。
 */
public final class SimulationLandsTab extends DimensionTab {

    private static final FamilySelectionStore STORE = new InMemorySelectionStore();

    public SimulationLandsTab(List<Identifier> dimensions) {
        super(dimensions);
    }

    @Override
    protected Component tabTitle() {
        return Component.translatable("createWorld.tab.infs-farlands.simulationlands.title");
    }

    @Override
    protected List<FamilyKind> families() {
        return List.of(FamilyKind.TERRAIN);
    }

    @Override
    protected FamilySelectionStore store() {
        return STORE;
    }
}

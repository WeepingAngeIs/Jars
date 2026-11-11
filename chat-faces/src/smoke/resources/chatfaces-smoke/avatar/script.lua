-- Original test fixture. All time advancement comes from normal Figura ticks.
vanilla_model.PLAYER:setVisible(false)
local portrait = models.face.Portrait
local ticks = 0
portrait:setColor(1, 0, 0)
events.TICK:register(function()
    ticks = ticks + 1
    local blue = math.floor(ticks / 10) % 2 == 1
    portrait:setColor(blue and 0 or 1, 0, blue and 1 or 0)
    portrait.Pulse:setScale(1, blue and 0.75 or 1, 1)
end, "original_smoke_animation")
